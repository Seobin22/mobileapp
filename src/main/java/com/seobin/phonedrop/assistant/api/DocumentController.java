package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.DocumentService;

/**
 * 문서 받기(빌드문서 v3.3 §4.2·§4.6). 목표 상세 [자료 올리기]와 채팅 [첨부]가 쓴다.
 * 채팅으로 올리면(from_chat=true) 목표 없이 올라가고 비서가 어느 목표 자료인지 판단한다.
 */
@RestController
@RequestMapping("/api/assistant/v1")
public class DocumentController {

    private final Tx tx;
    private final DocumentService documents;

    public DocumentController(Tx tx, DocumentService documents) {
        this.tx = tx;
        this.documents = documents;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "goal_id", required = false) Long goalId,
                                      @RequestParam(value = "task_id", required = false) Long taskId,
                                      @RequestParam(value = "block_id", required = false) Long blockId,
                                      @RequestParam(value = "message", required = false) String message,
                                      @RequestParam(value = "from_chat", defaultValue = "false") boolean fromChat)
            throws IOException {
        if (file.getSize() > DocumentService.MAX_BYTES) {
            throw ApiException.unprocessable("파일은 20MB 이하만 올릴 수 있어요");
        }
        byte[] data = file.getBytes();
        return tx.run(() -> documents.upload(file.getOriginalFilename(), data, goalId, taskId, blockId, message, fromChat));
    }

    @GetMapping("/documents")
    public Map<String, Object> list(@RequestParam(value = "goal_id", required = false) Long goalId) {
        return tx.run(() -> {
            List<DocumentService.Document> list = goalId == null ? documents.all(100) : documents.ofGoal(goalId);
            return Map.of("documents", list.stream().map(d -> documents.toMap(d, false)).toList());
        });
    }

    @GetMapping("/documents/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return tx.run(() -> documents.toMap(documents.find(id)
                .orElseThrow(() -> ApiException.notFound("자료#" + id + "이 없습니다")), true));
    }

    /** 원본 열기. */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) throws IOException {
        DocumentService.Document d = tx.run(() -> documents.find(id))
                .orElseThrow(() -> ApiException.notFound("자료#" + id + "이 없습니다"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''"
                        + URLEncoder.encode(d.filename(), StandardCharsets.UTF_8).replace("+", "%20"))
                .body(documents.fileBytes(d));
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        tx.exec(() -> documents.delete(id));
        return Map.of("ok", true, "id", id);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
