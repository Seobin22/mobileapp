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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        List<FileInfo> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(PrivateFileController::info)
                    .sorted((a, b) -> b.modifiedAt().compareTo(a.modifiedAt()))
                    .toList();
        }
        long used = files.stream().mapToLong(FileInfo::size).sum();
        return Map.of("files", files, "usedBytes", used, "quotaBytes", QUOTA_BYTES);
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

    /** 한 번에 올린 파일들을 zip 하나로 묶어 저장한다. 디스크로 바로 써서 메모리를 거의 쓰지 않는다. */
    private String uploadBundle(Path dir, MultipartFile[] files, String bundleName) throws IOException {
        List<MultipartFile> parts = Stream.of(files)
                .filter(f -> !f.isEmpty() && f.getOriginalFilename() != null)
                .toList();
        if (parts.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "저장할 파일이 없습니다.");
        }

        Path target = safePath(dir, bundleFileName(bundleName));
        long replaced = Files.isRegularFile(target) ? Files.size(target) : 0;
        long used = usedBytes(dir) - replaced;
        long total = parts.stream().mapToLong(MultipartFile::getSize).sum();
        if (used + total > QUOTA_BYTES) {
            throw new ResponseStatusException(CONTENT_TOO_LARGE, "저장 공간(1GB)이 부족합니다.");
        }

        Path tmp = Files.createTempFile(dir, ".upload-", ".tmp");
        try {
            Set<String> names = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (MultipartFile part : parts) {
                    zip.putNextEntry(new ZipEntry(uniqueName(names, baseName(part.getOriginalFilename()))));
                    try (InputStream in = part.getInputStream()) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            if (used + Files.size(tmp) > QUOTA_BYTES) {
                throw new ResponseStatusException(CONTENT_TOO_LARGE, "저장 공간(1GB)이 부족합니다.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return parts.size() + "개 파일을 " + target.getFileName() + " 로 묶어 저장했습니다.";
    }

    /** 묶음 이름: 입력한 이름(없으면 "묶음-날짜-시각") 뒤에 .zip. */
    static String bundleFileName(String requested) {
        String name = requested == null ? "" : baseName(requested.trim());
        if (name.isBlank()) {
            name = "묶음-" + LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name += ".zip";
        }
        return name;
    }

    /** zip 안에서 같은 이름이 겹치면 "이름 (2).확장자" 처럼 바꾼다. */
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

    @GetMapping("/files/download")
    public ResponseEntity<Resource> download(@RequestParam("name") String name, HttpServletRequest request)
            throws IOException {
        Path file = existing(userDir(session.require(request)), name);
        String encoded = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + asciiFallback(file.getFileName().toString())
                        + "\"; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/files")
    public String delete(@RequestParam("name") String name, HttpServletRequest request) throws IOException {
        Files.delete(existing(userDir(session.require(request)), name));
        return "삭제했습니다: " + name;
    }

    /** 화면에서 그대로 보여줄 수 있도록 오류 이유를 글자로 돌려준다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(e.getReason());
    }

    private Path userDir(User user) throws IOException {
        Path dir = root.resolve(Long.toString(user.id()));
        Files.createDirectories(dir);
        return dir;
    }

    private static Path existing(Path dir, String name) {
        Path file = safePath(dir, name);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(NOT_FOUND, "파일을 찾을 수 없습니다.");
        }
        return file;
    }

    /** 회원 폴더 바로 아래의 파일 이름만 허용한다(하위 폴더·상위 경로·숨김 임시파일 금지). */
    static Path safePath(Path dir, String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH
                || name.startsWith(".") || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new ResponseStatusException(BAD_REQUEST, "사용할 수 없는 파일 이름입니다.");
        }
        Path file;
        try {
            file = dir.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new ResponseStatusException(BAD_REQUEST, "사용할 수 없는 파일 이름입니다.");
        }
        if (!dir.equals(file.getParent())) {
            throw new ResponseStatusException(BAD_REQUEST, "잘못된 파일 경로입니다.");
        }
        return file;
    }

    private static long usedBytes(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    private static FileInfo info(Path p) {
        try {
            Instant modified = Files.getLastModifiedTime(p).toInstant();
            return new FileInfo(p.getFileName().toString(), Files.size(p), modified.toString());
        } catch (IOException e) {
            return new FileInfo(p.getFileName().toString(), 0, Instant.EPOCH.toString());
        }
    }
}
