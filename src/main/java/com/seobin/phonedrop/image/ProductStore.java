package com.seobin.phonedrop.image;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 상품 저장소. 상품마다 폴더 하나:
 * data/image/&lt;회원 id&gt;/&lt;상품 id&gt;/ { product.json, files/(원본·커버·상세), cache/(미리보기·작업용), tmp/(업로드 중) }
 */
@Component
public class ProductStore {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final Path root;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ProductStore(@Value("${image.data-dir:data/image}") String dataDir) {
        this.root = Path.of(dataDir).toAbsolutePath().normalize();
    }

    static String now() {
        return OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    Path userDir(long userId) {
        return root.resolve(Long.toString(userId));
    }

    Path dir(long userId, long productId) {
        return userDir(userId).resolve(Long.toString(productId));
    }

    Path filesDir(long userId, long productId) throws IOException {
        return Files.createDirectories(dir(userId, productId).resolve("files"));
    }

    Path cacheDir(long userId, long productId) throws IOException {
        return Files.createDirectories(dir(userId, productId).resolve("cache"));
    }

    Path tmpDir(long userId, long productId) throws IOException {
        return Files.createDirectories(dir(userId, productId).resolve("tmp"));
    }

    private Object lock(long userId, long productId) {
        return locks.computeIfAbsent(userId + "/" + productId, k -> new Object());
    }

    public List<Product> list(long userId) throws IOException {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Product> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Path f = p.resolve("product.json");
                if (Files.isRegularFile(f)) {
                    Product product = read(f);
                    if (product.type == null) {
                        out.add(product);
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Product p) -> p.createdAt).reversed());
        return out;
    }

    /** 동영상 만들기 작업 공간(회원당 하나). 없으면 만든다. */
    public Product slides(long userId) throws IOException {
        synchronized (lock(userId, -1)) {
            Path dir = userDir(userId);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.toList()) {
                        Path f = p.resolve("product.json");
                        if (Files.isRegularFile(f)) {
                            Product product = read(f);
                            if ("slides".equals(product.type)) {
                                return product;
                            }
                        }
                    }
                }
            }
            Product created = create(userId);
            return update(userId, created.id, p -> {
                p.type = "slides";
                return p;
            });
        }
    }

    public Product create(long userId) throws IOException {
        Path dir = Files.createDirectories(userDir(userId));
        synchronized (lock(userId, 0)) {
            long next = 1;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.matches("\\d{1,15}")) {
                        next = Math.max(next, Long.parseLong(n) + 1);
                    }
                }
            }
            Product product = new Product();
            product.id = next;
            product.createdAt = now();
            product.updatedAt = product.createdAt;
            Files.createDirectories(dir(userId, next));
            write(userId, product);
            return product;
        }
    }

    public Product get(long userId, long productId) {
        Path f = dir(userId, productId).resolve("product.json");
        if (!Files.isRegularFile(f)) {
            throw new ResponseStatusException(NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        synchronized (lock(userId, productId)) {
            return read(f);
        }
    }

    /** 상품을 읽고 바꾼 뒤 저장한다(같은 상품에 대한 변경은 한 번에 하나씩). */
    public <T> T update(long userId, long productId, Function<Product, T> change) {
        synchronized (lock(userId, productId)) {
            Product product = get(userId, productId);
            T result = change.apply(product);
            product.updatedAt = now();
            write(userId, product);
            return result;
        }
    }

    public void delete(long userId, long productId) throws IOException {
        Path dir = dir(userId, productId);
        if (!Files.isRegularFile(dir.resolve("product.json"))) {
            throw new ResponseStatusException(NOT_FOUND, "상품을 찾을 수 없습니다.");
        }
        synchronized (lock(userId, productId)) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** 회원이 쓰고 있는 전체 용량. */
    public long usedBytes(long userId) throws IOException {
        Path dir = userDir(userId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    long usableDiskBytes() {
        return root.toFile().getUsableSpace() > 0 ? root.toFile().getUsableSpace()
                : root.getRoot().toFile().getUsableSpace();
    }

    private Product read(Path f) {
        try {
            return json.readValue(Files.readString(f), Product.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(long userId, Product product) {
        try {
            Path dir = Files.createDirectories(dir(userId, product.id));
            Path tmp = Files.createTempFile(dir, ".product-", ".json");
            Files.writeString(tmp, json.writeValueAsString(product));
            Files.move(tmp, dir.resolve("product.json"), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
