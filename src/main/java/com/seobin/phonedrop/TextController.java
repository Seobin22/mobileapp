package com.seobin.phonedrop;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.TextStore.TextEntry;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

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

    /** 날짜를 주지 않으면 오늘(한국 시간) 기록을 돌려준다. 메인에서 지운 것은 빠진다. */
    @GetMapping("/texts")
    public List<TextEntry> getTexts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return store.findVisibleByDate(date != null ? date : TextStore.today());
    }

    /** 메인 화면에서 지우기. 숨기기만 하므로 관리자 페이지에서는 계속 보인다. */
    @DeleteMapping("/texts/{id}")
    public String hideText(@PathVariable long id) {
        if (!store.hide(id)) {
            throw new ResponseStatusException(NOT_FOUND, "이미 삭제된 텍스트입니다.");
        }
        return "삭제 완료";
    }

    @DeleteMapping("/texts")
    public String hideTextsOfDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return store.hideByDate(date) + "개 삭제 완료";
    }
}
