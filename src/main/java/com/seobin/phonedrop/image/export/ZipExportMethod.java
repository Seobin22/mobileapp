package com.seobin.phonedrop.image.export;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/** 내보내기: zip 파일로 다운로드. */
@Component
public class ZipExportMethod implements ExportMethod {

    @Override
    public String id() {
        return "zip";
    }

    @Override
    public String label() {
        return "zip 다운로드";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void export(Request request, HttpServletResponse response) throws IOException {
        String suffix = switch (request.scope()) {
            case "cover" -> "_cover";
            case "detail" -> "_detail";
            default -> "_all";
        };
        String zipName = request.baseName() + suffix + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20"));
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_SPEED); // JPG 는 이미 압축되어 있어 빠르게만 묶는다.
            for (File f : request.files()) {
                ZipEntry entry = new ZipEntry(f.name());
                entry.setLastModifiedTime(Files.getLastModifiedTime(f.path()));
                zip.putNextEntry(entry);
                Files.copy(f.path(), zip);
                zip.closeEntry();
            }
        }
    }
}
