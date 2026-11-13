package com.seobin.phonedrop.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.image.Product.Material;
import com.seobin.phonedrop.image.Product.Media;
import com.seobin.phonedrop.image.Product.Purchase;
import com.seobin.phonedrop.image.Product.SizeRow;
import com.seobin.phonedrop.image.export.ExportMethod;
import com.seobin.phonedrop.user.UserSession;
import com.seobin.phonedrop.user.UserStore.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CONTENT_TOO_LARGE;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 이미지 (seobin.page/image.html, image.seobin.page).
 * 회원 로그인 필요. 데이터는 data/image/&lt;회원 id&gt;/ 아래에 상품 단위로 저장한다.
 */
@RestController
@RequestMapping("/api/image")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    static final long QUOTA_BYTES = 10L * 1024 * 1024 * 1024;
    static final long MIN_FREE_DISK = 2L * 1024 * 1024 * 1024;
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;

    static final Map<String, Set<String>> MATERIAL_CHOICES = Map.of(
            "lining", Set.of("있음", "없음", "기모"),
            "stretch", Set.of("좋음", "보통", "없음"),
            "sheer", Set.of("없음", "약간", "있음"),
            "touch", Set.of("부드러움", "보통", "까실함"),
            "fit", Set.of("타이트", "정사이즈", "루즈"),
            "thickness", Set.of("도톰", "보통", "얇음"));
    static final List<String> SEASONS = List.of("봄/가을", "여름", "겨울");

    public record ProductEdit(Purchase purchase, List<SizeRow> sizes, Material material) {
    }

    public record CoverRequest(int x, int y, int size) {
    }

    public record CaptureRequest(double time) {
    }

    private final UserSession session;
    private final ProductStore store;
    private final MediaService media;
    private final List<ExportMethod> exportMethods;
    /** 이 기능을 쓸 수 있는 회원 아이디. 비어 있으면 아무도 못 쓴다. */
    private final Set<String> allowedUsers;

    public ImageController(UserSession session, ProductStore store, MediaService media, List<ExportMethod> exportMethods,
                           @Value("${image.allowed-users:}") String allowedUsers) {
        this.session = session;
        this.store = store;
        this.media = media;
        this.exportMethods = exportMethods.stream().sorted(Comparator.comparingInt(ExportMethod::order)).toList();
        this.allowedUsers = Stream.of(allowedUsers.split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 로그인(401)과 사용 권한(403)을 확인한다. 권한은 application.properties 의 image.allowed-users 로 준다. */
    private User allowed(HttpServletRequest request) {
        User user = session.require(request);
        if (!allowedUsers.contains(user.username().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(FORBIDDEN, "이 계정은 이미지 기능을 쓸 권한이 없습니다.");
        }
        return user;
    }

    // ================================================================ 상품

    @GetMapping("/products")
    public Map<String, Object> list(HttpServletRequest request) throws IOException {
        User user = allowed(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Product p : store.list(user.id())) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", p.id);
            s.put("name", p.purchase.name);
            s.put("store", p.purchase.store);
            s.put("cost", p.purchase.cost);
            s.put("createdAt", p.createdAt);
            s.put("updatedAt", p.updatedAt);
            s.put("original", p.media.stream().filter(m -> m.kind.equals("original")).count());
            s.put("cover", p.media.stream().filter(m -> m.kind.equals("cover")).count());
            s.put("detail", p.media.stream().filter(m -> m.kind.equals("detail")).count());
            s.put("thumb", p.media.stream().filter(m -> m.kind.equals("cover")).findFirst()
                    .or(() -> p.media.stream().filter(m -> m.kind.equals("original")).findFirst())
                    .map(m -> m.id).orElse(null));
            items.add(s);
        }
        return Map.of("products", items, "usedBytes", store.usedBytes(user.id()), "quotaBytes", QUOTA_BYTES);
    }

    @PostMapping("/products")
    public Product create(HttpServletRequest request) throws IOException {
        return store.create(allowed(request).id());
    }

    @GetMapping("/products/{pid}")
    public Product get(@PathVariable long pid, HttpServletRequest request) {
        return store.get(allowed(request).id(), pid);
    }

    /** 사입·사이즈·소재 정보 저장. 사입명 등 글자는 받은 그대로 저장한다(trim 하지 않음). */
    @PutMapping("/products/{pid}")
    public Product update(@PathVariable long pid, @RequestBody ProductEdit edit, HttpServletRequest request) {
        User user = allowed(request);
        return store.update(user.id(), pid, p -> {
            if (edit.purchase() != null) {
                p.purchase.name = limit(edit.purchase().name, 200);
                p.purchase.store = limit(edit.purchase().store, 100);
                p.purchase.cost = limit(edit.purchase().cost, 30);
            }
            if (edit.sizes() != null) {
                if (edit.sizes().size() > 30) {
                    throw new ResponseStatusException(BAD_REQUEST, "사이즈 행은 30개까지입니다.");
                }
                List<SizeRow> rows = new ArrayList<>();
                for (SizeRow r : edit.sizes()) {
                    if (r == null) {
                        continue;
                    }
                    SizeRow c = new SizeRow();
                    c.label = limit(r.label, 30);
                    c.total = limit(r.total, 20);
                    c.shoulder = limit(r.shoulder, 20);
                    c.chest = limit(r.chest, 20);
                    c.sleeve = limit(r.sleeve, 20);
                    c.sleeveWidth = limit(r.sleeveWidth, 20);
                    c.armhole = limit(r.armhole, 20);
                    c.hem = limit(r.hem, 20);
                    rows.add(c);
                }
                p.sizes = rows;
            }
            if (edit.material() != null) {
                Material in = edit.material();
                Material m = new Material();
                m.lining = choice("lining", in.lining);
                m.stretch = choice("stretch", in.stretch);
                m.sheer = choice("sheer", in.sheer);
                m.touch = choice("touch", in.touch);
                m.fit = choice("fit", in.fit);
                m.thickness = choice("thickness", in.thickness);
                m.season = in.season == null ? new ArrayList<>()
                        : new ArrayList<>(SEASONS.stream().filter(in.season::contains).toList());
                p.material = m;
            }
            return p;
        });
    }

    @DeleteMapping("/products/{pid}")
    public Map<String, Object> delete(@PathVariable long pid, HttpServletRequest request) throws IOException {
        store.delete(allowed(request).id(), pid);
        return Map.of("deleted", pid);
    }

    // ================================================================ 업로드 (조각으로 나눠 올리기)

    /**
     * 파일을 조각(최대 16MB)으로 나눠 차례대로 올린다. offset 은 지금까지 받은 바이트 수와 같아야 한다.
     * 마지막 조각을 받으면 분석해서 원본 칸(target=original) 또는 장끼 사진(target=receipt)으로 저장한다.
     */
    @PutMapping("/products/{pid}/upload/{uploadId}")
    public Map<String, Object> upload(@PathVariable long pid, @PathVariable String uploadId,
                                      @RequestParam String name, @RequestParam long offset, @RequestParam long total,
                                      @RequestParam(defaultValue = "original") String target,
                                      HttpServletRequest request) throws IOException {
        User user = allowed(request);
        store.get(user.id(), pid);
        if (!uploadId.matches("[a-zA-Z0-9]{8,40}")) {
            throw new ResponseStatusException(BAD_REQUEST, "잘못된 업로드 id");
        }
        if (!target.equals("original") && !target.equals("receipt")) {
            throw new ResponseStatusException(BAD_REQUEST, "잘못된 대상");
        }
        if (total <= 0 || total > MAX_FILE_BYTES) {
            throw new ResponseStatusException(CONTENT_TOO_LARGE, "파일 하나는 2GB 까지 올릴 수 있습니다.");
        }
        Path part = store.tmpDir(user.id(), pid).resolve(uploadId + ".part");
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (offset == 0 && have > 0) {
            Files.delete(part); // 처음부터 다시 올리는 경우
            have = 0;
        }
        if (offset != have) {
            throw new ResponseStatusException(CONFLICT, "업로드 위치가 맞지 않습니다(받은 크기 " + have + ").");
        }
        if (offset == 0) {
            if (store.usedBytes(user.id()) + total > QUOTA_BYTES) {
                throw new ResponseStatusException(INSUFFICIENT_STORAGE, "저장 공간(10GB)이 부족합니다.");
            }
            if (store.usableDiskBytes() - total < MIN_FREE_DISK) {
                throw new ResponseStatusException(INSUFFICIENT_STORAGE, "서버 디스크 공간이 부족합니다.");
            }
        }
        long received;
        try (InputStream in = request.getInputStream();
             OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            received = copyLimited(in, out, Math.min(MAX_CHUNK_BYTES, total - offset));
        }
        long size = offset + received;
        if (size < total) {
            return Map.of("done", false, "received", size);
        }
        String safeName = cleanName(name);
        try {
            if (target.equals("receipt")) {
                return Map.of("done", true, "product", saveReceipt(user.id(), pid, part, safeName));
            }
            return Map.of("done", true, "media", saveOriginal(user.id(), pid, part, safeName));
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private Media saveOriginal(long userId, long pid, Path part, String name) throws IOException {
        String id = store.update(userId, pid, p -> "m" + (++p.seq));
        Media m = new Media();
        m.id = id;
        m.kind = "original";
        m.name = name;
        m.file = id + "." + extension(name);
        m.createdAt = ProductStore.now();
        Path file = store.filesDir(userId, pid).resolve(m.file);
        Files.move(part, file);
        try {
            media.analyze(userId, pid, m);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return addMedia(userId, pid, m);
    }

    private Media addMedia(long userId, long pid, Media m) {
        return store.update(userId, pid, p -> {
            p.media.add(m);
            return m;
        });
    }

    private Product saveReceipt(long userId, long pid, Path part, String name) throws IOException {
        String format = PhotoMeta.sniff(part);
        if (format == null || format.equals("VIDEO")) {
            throw new ResponseStatusException(BAD_REQUEST, "장끼는 사진 파일만 올릴 수 있습니다.");
        }
        Path dir = store.dir(userId, pid);
        String file = "receipt." + extension(name);
        Path cache = store.cacheDir(userId, pid);
        Path preview = cache.resolve("receipt_1600.tmp.jpg");
        media.receiptPreview(part, format, preview);
        Product before = store.get(userId, pid);
        if (before.purchase.receiptFile != null) {
            Files.deleteIfExists(dir.resolve(before.purchase.receiptFile));
        }
        Files.move(part, dir.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(preview, cache.resolve("receipt_1600.jpg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return store.update(userId, pid, p -> {
            p.purchase.receiptFile = file;
            p.purchase.receiptName = name;
            return p;
        });
    }

    @GetMapping("/products/{pid}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable long pid,
                                            @RequestParam(defaultValue = "false") boolean original,
                                            HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Product p = store.get(user.id(), pid);
        if (p.purchase.receiptFile == null) {
            throw new ResponseStatusException(NOT_FOUND, "장끼 사진이 없습니다.");
        }
        if (original) {
            return file(store.dir(user.id(), pid).resolve(p.purchase.receiptFile), p.purchase.receiptName, true);
        }
        return file(store.cacheDir(user.id(), pid).resolve("receipt_1600.jpg"), null, false);
    }

    @DeleteMapping("/products/{pid}/receipt")
    public Product deleteReceipt(@PathVariable long pid, HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Product p = store.get(user.id(), pid);
        if (p.purchase.receiptFile != null) {
            Files.deleteIfExists(store.dir(user.id(), pid).resolve(p.purchase.receiptFile));
            Files.deleteIfExists(store.cacheDir(user.id(), pid).resolve("receipt_1600.jpg"));
        }
        return store.update(user.id(), pid, x -> {
            x.purchase.receiptFile = null;
            x.purchase.receiptName = null;
            return x;
        });
    }

    // ================================================================ 미디어

    @GetMapping("/products/{pid}/media/{mid}/file")
    public ResponseEntity<Resource> mediaFile(@PathVariable long pid, @PathVariable String mid,
                                              @RequestParam(defaultValue = "false") boolean download,
                                              HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media m = find(store.get(user.id(), pid), mid);
        return file(store.filesDir(user.id(), pid).resolve(m.file), m.name, download);
    }

    @GetMapping("/products/{pid}/media/{mid}/preview")
    public ResponseEntity<Resource> preview(@PathVariable long pid, @PathVariable String mid,
                                            @RequestParam(defaultValue = "400") int size,
                                            HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media m = find(store.get(user.id(), pid), mid);
        int s = size > 400 ? 1600 : 400;
        Path p = store.cacheDir(user.id(), pid).resolve(m.id + "_" + s + ".jpg");
        if (!Files.isRegularFile(p)) {
            throw new ResponseStatusException(NOT_FOUND, "미리보기가 없습니다.");
        }
        return file(p, null, false);
    }

    @GetMapping("/products/{pid}/media/{mid}/proxy")
    public ResponseEntity<Resource> proxyFile(@PathVariable long pid, @PathVariable String mid,
                                              HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media m = find(store.get(user.id(), pid), mid);
        Path p = store.cacheDir(user.id(), pid).resolve(m.id + "_proxy.mp4");
        if (!Files.isRegularFile(p)) {
            throw new ResponseStatusException(NOT_FOUND, "미리보기용 영상이 아직 없습니다.");
        }
        return file(p, null, false);
    }

    @PostMapping("/products/{pid}/media/{mid}/proxy")
    public Media makeProxy(@PathVariable long pid, @PathVariable String mid, HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media m = find(store.get(user.id(), pid), mid);
        if (!"video".equals(m.type)) {
            throw new ResponseStatusException(BAD_REQUEST, "영상이 아닙니다.");
        }
        if ("making".equals(m.proxy) || "ready".equals(m.proxy)) {
            return m;
        }
        Media updated = store.update(user.id(), pid, p -> {
            Media x = find(p, mid);
            x.proxy = "making";
            return x;
        });
        media.startProxy(user.id(), pid, updated);
        return updated;
    }

    @PostMapping("/products/{pid}/media/{mid}/cover")
    public Media cover(@PathVariable long pid, @PathVariable String mid, @RequestBody CoverRequest body,
                       HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media original = find(store.get(user.id(), pid), mid);
        requireOriginal(original);
        return addMedia(user.id(), pid, media.makeCover(user.id(), pid, original, body.x(), body.y(), body.size()));
    }

    @PostMapping("/products/{pid}/media/{mid}/detail")
    public Media detail(@PathVariable long pid, @PathVariable String mid, HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media original = find(store.get(user.id(), pid), mid);
        requireOriginal(original);
        return addMedia(user.id(), pid, media.makeDetail(user.id(), pid, original));
    }

    @PostMapping("/products/{pid}/media/{mid}/capture")
    public Media capture(@PathVariable long pid, @PathVariable String mid, @RequestBody CaptureRequest body,
                         HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media video = find(store.get(user.id(), pid), mid);
        return addMedia(user.id(), pid, media.capture(user.id(), pid, video, body.time()));
    }

    @DeleteMapping("/products/{pid}/media/{mid}")
    public Map<String, Object> deleteMedia(@PathVariable long pid, @PathVariable String mid,
                                           HttpServletRequest request) throws IOException {
        User user = allowed(request);
        Media m = store.update(user.id(), pid, p -> {
            Media x = find(p, mid);
            p.media.remove(x);
            return x;
        });
        Files.deleteIfExists(store.filesDir(user.id(), pid).resolve(m.file));
        try (Stream<Path> s = Files.list(store.cacheDir(user.id(), pid))) {
            for (Path c : s.filter(c -> c.getFileName().toString().startsWith(m.id + "_")).toList()) {
                Files.deleteIfExists(c);
            }
        }
        return Map.of("deleted", mid);
    }

    // ================================================================ 내보내기

    @GetMapping("/export-methods")
    public List<Map<String, Object>> exportMethods(HttpServletRequest request) {
        allowed(request);
        return exportMethods.stream().map(e -> Map.<String, Object>of("id", e.id(), "label", e.label())).toList();
    }

    @GetMapping("/products/{pid}/export/{method}")
    public void export(@PathVariable long pid, @PathVariable String method,
                       @RequestParam(defaultValue = "all") String scope,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        User user = allowed(request);
        ExportMethod exporter = exportMethods.stream().filter(e -> e.id().equals(method)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "없는 내보내기 방식입니다."));
        if (!Set.of("cover", "detail", "all").contains(scope)) {
            throw new ResponseStatusException(BAD_REQUEST, "범위는 cover, detail, all 중 하나입니다.");
        }
        Product p = store.get(user.id(), pid);
        String base = exportBaseName(p);
        Path files = store.filesDir(user.id(), pid);
        List<ExportMethod.File> list = new ArrayList<>();
        for (String kind : List.of("cover", "detail")) {
            if (!scope.equals("all") && !scope.equals(kind)) {
                continue;
            }
            int n = 0;
            for (Media m : p.media) {
                if (m.kind.equals(kind)) {
                    list.add(new ExportMethod.File(base + "_" + kind + (++n) + "." + extension(m.file),
                            files.resolve(m.file), kind));
                }
            }
        }
        if (list.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "내보낼 이미지가 없습니다.");
        }
        exporter.export(new ExportMethod.Request(pid, base, scope, list), response);
    }

    /** 파일명 앞부분: 사입명의 첫 "/" 앞 부분(예: 커프스T/검정/FREE → 커프스T). 파일명에 못 쓰는 글자는 _ 로. */
    static String exportBaseName(Product p) {
        String name = p.purchase.name == null ? "" : p.purchase.name;
        int slash = name.indexOf('/');
        String head = (slash >= 0 ? name.substring(0, slash) : name).strip();
        head = head.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return head.isEmpty() ? "상품" + p.id : head;
    }

    // ================================================================ 공통

    private static Media find(Product p, String mid) {
        return p.media.stream().filter(m -> m.id.equals(mid)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "파일을 찾을 수 없습니다."));
    }

    private static void requireOriginal(Media m) {
        if (!m.kind.equals("original")) {
            throw new ResponseStatusException(BAD_REQUEST, "원본 칸의 사진에서만 만들 수 있습니다.");
        }
    }

    private static ResponseEntity<Resource> file(Path path, String downloadName, boolean download) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(NOT_FOUND, "파일이 없습니다.");
        }
        String ext = extension(path.getFileName().toString());
        MediaType type = switch (ext) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "heic", "heif" -> MediaType.parseMediaType("image/heic");
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "mp4", "m4v" -> MediaType.parseMediaType("video/mp4");
            // 브라우저 재생을 위해 mov 는 mp4 로 알려준다(아이폰 mov 는 mp4 와 같은 구조).
            case "mov" -> MediaType.parseMediaType(download ? "video/quicktime" : "video/mp4");
            case "webm" -> MediaType.parseMediaType("video/webm");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.noCache().cachePrivate());
        if (download && downloadName != null) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''"
                    + URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return b.body(new FileSystemResource(path));
    }

    private static long copyLimited(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new ResponseStatusException(CONTENT_TOO_LARGE, "조각이 너무 큽니다.");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    static String cleanName(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (base.isEmpty()) {
            base = "file";
        }
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return ext.matches("[a-z0-9]{1,5}") ? ext : "bin";
    }

    private static String limit(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String choice(String field, String value) {
        return value != null && MATERIAL_CHOICES.get(field).contains(value) ? value : "";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> statusError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> ioError(IOException e) {
        log.warn("이미지 처리 실패: {}", e.getMessage());
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage() == null ? "처리 중 오류가 났습니다." : e.getMessage()));
    }
}
