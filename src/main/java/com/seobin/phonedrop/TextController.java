package com.seobin.phonedrop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class TextController {

    private final List<String> texts = new CopyOnWriteArrayList<>();

    @PostMapping("/texts")
    public String addText(@RequestBody String text) {

        String trimmedText = text.trim();

        if (trimmedText.isEmpty()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "빈 텍스트는 전송할 수 없습니다."
            );
        }

        texts.add(0, trimmedText);

        return "텍스트 저장 완료";
    }

    @GetMapping("/texts")
    public List<String> getTexts() {
        return texts;
    }

    @DeleteMapping("/texts")
    public String deleteTexts() {
        texts.clear();
        return "텍스트 전체 삭제 완료";
    }
}