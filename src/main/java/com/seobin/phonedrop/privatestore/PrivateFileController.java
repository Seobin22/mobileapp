package com.seobin.phonedrop.privatestore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.user.UserSession;
import com.seobin.phonedrop.user.UserStore.User;

import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONTENT_TOO_LARGE;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 회원 개인 저장소 (seobin.page/private.html).
 * 로그인한 회원 본인의 파일만 data/private/&lt;회원 id&gt;/ 에 저장·조회·삭제한다.
 * "묶어서 올리기" 로 올린 파일들은 같은 폴더 아래 &lt;묶음 이름&gt;/ 폴더에 낱개 그대로 저장한다(압축하지 않음).
 * 공개 파일 전송(uploads/, /files)과는 완전히 따로 동작한다.
 */
@RestController
@RequestMapping("/api/private")
public class PrivateFileController {

    /** 회원 한 명이 쓸 수 있는 전체 용량. */
    static final long QUOTA_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 200;

    public record FileInfo(String name, long size, String modifiedAt) {
    }

    public record BundleInfo(String name, List<FileInfo> files, long size, String modifiedAt) {
    }

    private final UserSession session;
    private final Path root;

    public PrivateFileController(UserSession session,
                                 @Value("${private.data-dir:data/private}") String dataDir) {
        this.session = session;
        this.root = Path.of(dataDir).toAbsolutePath().normalize();
    }

    @GetMapping("/files")
    public Map<String, Object> list(HttpServletRequest request) throws IOException {
        Path dir = userDir(session.require(request));
        List<FileInfo> files = filesIn(dir);
        List<BundleInfo> bundles = new ArrayList<>();
        for (Path b : visible(dir).filter(Files::isDirectory).toList()) {
            List<FileInfo> inside = filesIn(b).stream().sorted(Comparator.comparing(FileInfo::name)).toList();
            bundles.add(new BundleInfo(b.getFileName().toString(), inside,
                    inside.stream().mapToLong(FileInfo::size).sum(), info(b).modifiedAt()));
        }
        bundles.sort(Comparator.comparing(BundleInfo::modifiedAt).reversed());
        long used = files.stream().mapToLong(FileInfo::size).sum()
                + bundles.stream().mapToLong(BundleInfo::size).sum();
        return Map.of("files", files, "bundles", bundles, "usedBytes", used, "quotaBytes", QUOTA_BYTES);
    }

    @PostMapping("/files")
    public String upload(@RequestParam("files") MultipartFile[] files,
                         @RequestParam(value = "bundle", defaultValue = "false") boolean bundle,
                         @RequestParam(value = "bundleName", required = false) String bundleName,
                         HttpServletRequest request) throws IOException {
        Path dir = userDir(session.require(request));
        if (bundle) {
            return uploadBundle(dir, files, bundleName);
        }
        long used = usedBytes(dir);
        int saved = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty() || file.getOriginalFilename() == null) {
                continue;
            }
            Path target = safePath(dir, baseName(file.getOriginalFilename()));
            if (Files.isDirectory(target)) {
                throw new ResponseStatusException(BAD_REQUEST, "같은 이름의 묶음이 있습니다: " + target.getFileName());
            }
            long replaced = Files.isRegularFile(target) ? Files.size(target) : 0;

            if (used - replaced + file.getSize() > QUOTA_BYTES) {
                throw new ResponseStatusException(CONTENT_TOO_LARGE,
                        "저장 공간(1GB)이 부족합니다. " + saved + "개까지 저장했습니다.");
            }
            // 임시 파일에 먼저 쓰고 옮겨서, 중간에 끊겨도 기존 파일이 깨지지 않게 한다.
            Path tmp = Files.createTempFile(dir, ".upload-", ".tmp");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            used += file.getSize() - replaced;
            saved++;
        }

        if (saved == 0) {
            throw new ResponseStatusException(BAD_REQUEST, "저장할 파일이 없습니다.");
        }
        return saved + "개 파일을 내 저장소에 저장했습니다.";
    }

    /** 한 번에 올린 파일들을 묶음 폴더 하나에 낱개 그대로 저장한다. 이름이 겹치면 "이름 (2)" 로 새로 만든다. */
    private String uploadBundle(Path dir, MultipartFile[] files, String bundleName) throws IOException {
        List<MultipartFile> parts = Stream.of(files)
                .filter(f -> !f.isEmpty() && f.getOriginalFilename() != null)
                .toList();
        if (parts.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "저장할 파일이 없습니다.");
        }
        long total = parts.stream().mapToLong(MultipartFile::getSize).sum();
        if (usedBytes(dir) + total > QUOTA_BYTES) {
            throw new ResponseStatusException(CONTENT_TOO_LARGE, "저장 공간(1GB)이 부족합니다.");
        }

        String base = bundleBaseName(bundleName);
        safePath(dir, base);

        // 숨김 임시 폴더에 다 쓴 뒤 한 번에 옮겨서, 중간에 끊긴 묶음이 목록에 보이지 않게 한다.
        Path tmp = Files.createTempDirectory(dir, ".upload-");
        try {
            Set<String> names = new HashSet<>();
            for (MultipartFile part : parts) {
                Path target = safePath(tmp, uniqueName(names, baseName(part.getOriginalFilename())));
                try (InputStream in = part.getInputStream()) {
                    Files.copy(in, target);
                }
            }
            Path target = freeName(dir, base);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return parts.size() + "개 파일을 묶음 '" + target.getFileName() + "' 으로 저장했습니다.";
        } finally {
            deleteFlat(tmp);
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<Resource> download(@RequestParam("name") String name, HttpServletRequest request)
            throws IOException {
        return send(existingFile(userDir(session.require(request)), name));
    }

    @DeleteMapping("/files")
    public String delete(@RequestParam("name") String name, HttpServletRequest request) throws IOException {
        Files.delete(existingFile(userDir(session.require(request)), name));
        return "삭제했습니다: " + name;
    }

    /** 묶음 안의 파일 하나. 화면의 "묶음 다운로드" 는 이것을 파일마다 차례로 부른다. */
    @GetMapping("/bundles/download")
    public ResponseEntity<Resource> downloadFromBundle(@RequestParam("bundle") String bundle,
                                                       @RequestParam("name") String name,
                                                       HttpServletRequest request) throws IOException {
        return send(existingFile(existingBundle(userDir(session.require(request)), bundle), name));
    }

    @DeleteMapping("/bundles")
    public String deleteBundle(@RequestParam("bundle") String bundle, HttpServletRequest request) throws IOException {
        Path b = existingBundle(userDir(session.require(request)), bundle);
        deleteFlat(b);
        return "묶음을 삭제했습니다: " + bundle;
    }

    /** 화면에서 그대로 보여줄 수 있도록 오류 이유를 글자로 돌려준다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(e.getReason());
    }

    private static ResponseEntity<Resource> send(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + asciiFallback(fileName)
                        + "\"; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(file));
    }

    /** 묶음 이름: 입력한 이름, 없으면 "묶음-날짜-시각". */
    static String bundleBaseName(String requested) {
        String name = requested == null ? "" : baseName(requested.trim());
        if (name.isBlank()) {
            name = "묶음-" + LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        return name;
    }

    /** 이미 같은 이름(파일이나 묶음)이 있으면 "이름 (2)", "이름 (3)" … 중 빈 이름. */
    private static Path freeName(Path dir, String base) {
        Path candidate = safePath(dir, base);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = safePath(dir, base + " (" + i + ")");
        }
        return candidate;
    }

    /** 한 묶음 안에서 같은 이름이 겹치면 "이름 (2).확장자" 처럼 바꾼다. */
    private static String uniqueName(Set<String> used, String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); i++) {
            candidate = stem + " (" + i + ")" + ext;
        }
        return candidate;
    }

    /** filename* 를 모르는 브라우저용 이름: 한글 등은 _ 로 바꾸고 확장자는 남긴다. */
    private static String asciiFallback(String name) {
        return name.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    /** 브라우저가 경로를 붙여 보내도 마지막 파일 이름만 쓴다. */
    private static String baseName(String original) {
        return original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
    }

    private Path userDir(User user) throws IOException {
        Path dir = root.resolve(Long.toString(user.id()));
        Files.createDirectories(dir);
        return dir;
    }

    private static Path existingFile(Path dir, String name) {
        Path file = safePath(dir, name);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(NOT_FOUND, "파일을 찾을 수 없습니다.");
        }
        return file;
    }

    private static Path existingBundle(Path dir, String name) {
        Path bundle = safePath(dir, name);
        if (!Files.isDirectory(bundle)) {
            throw new ResponseStatusException(NOT_FOUND, "묶음을 찾을 수 없습니다.");
        }
        return bundle;
    }

    /** 폴더 바로 아래의 이름만 허용한다(하위 경로·상위 경로·숨김 임시파일 금지). */
    static Path safePath(Path dir, String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH
                || name.startsWith(".") || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new ResponseStatusException(BAD_REQUEST, "사용할 수 없는 이름입니다.");
        }
        Path file;
        try {
            file = dir.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(BAD_REQUEST, "사용할 수 없는 이름입니다.");
        }
        if (!dir.equals(file.getParent())) {
            throw new ResponseStatusException(BAD_REQUEST, "잘못된 경로입니다.");
        }
        return file;
    }

    /** 이름이 "." 으로 시작하지 않는 항목(임시 파일·폴더 제외). */
    private static Stream<Path> visible(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).toList().stream();
        }
    }

    private static List<FileInfo> filesIn(Path dir) throws IOException {
        return visible(dir).filter(Files::isRegularFile)
                .map(PrivateFileController::info)
                .sorted(Comparator.comparing(FileInfo::modifiedAt).reversed())
                .toList();
    }

    /** 회원 폴더의 파일 + 묶음 폴더 안 파일 + 올리는 중인 임시 파일까지 합한 크기. */
    private static long usedBytes(Path dir) throws IOException {
        long total = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> inner = Files.list(p)) {
                        for (Path q : inner.toList()) {
                            total += sizeOf(q);
                        }
                    }
                } else {
                    total += sizeOf(p);
                }
            }
        }
        return total;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 묶음 폴더는 파일만 담고 하위 폴더가 없으므로 한 단계만 지운다. */
    private static void deleteFlat(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
    }

    private static FileInfo info(Path p) {
        try {
            Instant modified = Files.getLastModifiedTime(p).toInstant();
            return new FileInfo(p.getFileName().toString(), sizeOf(p), modified.toString());
        } catch (IOException e) {
            return new FileInfo(p.getFileName().toString(), 0, Instant.EPOCH.toString());
        }
    }
}
