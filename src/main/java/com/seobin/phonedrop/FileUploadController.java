package com.seobin.phonedrop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FileUploadController {

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {

        Path uploadFolder = Paths.get("uploads");
        Files.createDirectories(uploadFolder);

        String fileName = Paths.get(file.getOriginalFilename())
                .getFileName()
                .toString();

        Path savePath = uploadFolder.resolve(fileName);

        file.transferTo(savePath);

        return "파일 저장 완료: " + fileName;
    }
}