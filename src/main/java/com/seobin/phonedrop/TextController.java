package com.seobin.phonedrop;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.TextStore.TextEntry;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class TextController {

    private static final int MAX_LENGTH = 100_000;

    private final TextStore store;

    public TextController(TextStore store) {
        this.store = store;
    }

    @PostMapping("/texts")
    public String addText(@RequestBody String text) {

        String trimmedText = text.trim();

        if (trimmedText.isEmpty()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "빈 텍스트는 전송할 수 없습니다."
            );
        }

        if (trimmedText.length() > MAX_LENGTH) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "텍스트가 너무 깁니다."
            );
        }

        store.add(trimmedText);

        return "텍스트 저장 완료";
    }

    /** 날짜를 주지 않으면 오늘(한국 시간) 기록을 돌려준다. 삭제는 관리자 페이지에서만 한다. */
    @GetMapping("/texts")
    public List<TextEntry> getTexts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return store.findByDate(date != null ? date : TextStore.today());
    }
}
