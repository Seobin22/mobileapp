package com.seobin.phonedrop;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class FileUploadController {

    private final Path uploadFolder =
            Paths.get("uploads").toAbsolutePath().normalize();

    @PostMapping("/files")
    public String upload(@RequestParam("files") MultipartFile[] files)
            throws IOException {

        Files.createDirectories(uploadFolder);

        if (files.length == 0) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "업로드할 파일이 없습니다."
            );
        }

        int savedCount = 0;

        for (MultipartFile file : files) {

            if (file.isEmpty()) {
                continue;
            }

            String originalName = file.getOriginalFilename();

            if (originalName == null || originalName.isBlank()) {
                continue;
            }

            String fileName = Paths.get(originalName)
                    .getFileName()
                    .toString();

            Path savePath = getSafePath(fileName);

            Files.copy(
                    file.getInputStream(),
                    savePath,
                    StandardCopyOption.REPLACE_EXISTING
            );

            savedCount++;
        }

        if (savedCount == 0) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "저장할 수 있는 파일이 없습니다."
            );
        }

        return savedCount + "개 파일 저장 완료";
    }

    @GetMapping("/files")
    public List<String> getFiles() throws IOException {

        Files.createDirectories(uploadFolder);

        try (var files = Files.list(uploadFolder)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<Resource> download(
            @RequestParam("name") String fileName
    ) throws IOException {

        Path filePath = getSafePath(fileName);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "파일을 찾을 수 없습니다."
            );
        }

        Resource resource = new UrlResource(filePath.toUri());

        String encodedName = URLEncoder
                .encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName
                )
                .body(resource);
    }

    @DeleteMapping("/files")
    public String delete(@RequestParam("name") String fileName)
            throws IOException {

        Path filePath = getSafePath(fileName);

        if (!Files.deleteIfExists(filePath)) {
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "삭제할 파일을 찾을 수 없습니다."
            );
        }

        return "파일 삭제 완료: " + fileName;
    }

    private Path getSafePath(String fileName) {

        Path filePath = uploadFolder
                .resolve(fileName)
                .normalize();

        if (!filePath.startsWith(uploadFolder)) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "잘못된 파일 경로입니다."
            );
        }

        return filePath;
    }
}