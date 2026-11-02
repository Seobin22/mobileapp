package com.seobin.phonedrop.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.TextStore;
import com.seobin.phonedrop.TextStore.DateCount;
import com.seobin.phonedrop.TextStore.TextEntry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 관리자 페이지(admin.seobin.page) API. 로그인하면 세션에 아이디를 넣고, 나머지 API는 세션이 있어야 한다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final String SESSION_KEY = "admin";
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_SECONDS = 300;
    private static final int MIN_PASSWORD_LENGTH = 8;

    public record LoginRequest(String username, String password) {
    }

    public record AccountRequest(String currentPassword, String newUsername, String newPassword) {
    }

    private final AdminAccount account;
    private final TextStore store;

    // 로그인 실패가 이어지면 잠시 모든 로그인을 막는다(nginx 뒤라 접속자 IP 구분이 어렵다).
    private int failures;
    private Instant lockedUntil = Instant.EPOCH;

    public AdminController(AdminAccount account, TextStore store) {
        this.account = account;
        this.store = store;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        synchronized (this) {
            if (Instant.now().isBefore(lockedUntil)) {
                throw new ResponseStatusException(TOO_MANY_REQUESTS, "로그인 실패가 많아 5분 동안 잠겼습니다.");
            }

            if (body.username() == null || body.password() == null
                    || !account.matches(body.username(), body.password())) {
                failures++;
                if (failures >= MAX_FAILURES) {
                    failures = 0;
                    lockedUntil = Instant.now().plusSeconds(LOCK_SECONDS);
                }
                throw new ResponseStatusException(UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다.");
            }

            failures = 0;
        }

        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_KEY, body.username());
        return Map.of("username", body.username());
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "로그아웃 완료";
    }

    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        return Map.of("username", requireAdmin(request));
    }

    @GetMapping("/texts")
    public List<TextEntry> texts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        requireAdmin(request);
        return store.findByDate(date != null ? date : TextStore.today());
    }

    @GetMapping("/texts/dates")
    public List<DateCount> dates(HttpServletRequest request) {
        requireAdmin(request);
        return store.dates();
    }

    @DeleteMapping("/texts/{id}")
    public String deleteText(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        if (!store.delete(id)) {
            throw new ResponseStatusException(NOT_FOUND, "이미 삭제된 텍스트입니다.");
        }
        return "삭제 완료";
    }

    @DeleteMapping("/texts")
    public String deleteTextsOfDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        requireAdmin(request);
        return store.deleteByDate(date) + "개 삭제 완료";
    }

    @PostMapping("/account")
    public String changeAccount(@RequestBody AccountRequest body, HttpServletRequest request) throws IOException {
        String current = requireAdmin(request);

        if (body.currentPassword() == null || !account.matches(current, body.currentPassword())) {
            throw new ResponseStatusException(BAD_REQUEST, "현재 비밀번호가 맞지 않습니다.");
        }

        String newUsername = body.newUsername() == null ? "" : body.newUsername().trim();
        if (newUsername.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "새 아이디를 입력하세요.");
        }
        if (body.newPassword() == null || body.newPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "새 비밀번호는 8자 이상이어야 합니다.");
        }

        account.change(newUsername, body.newPassword());
        request.getSession().setAttribute(SESSION_KEY, newUsername);
        return "아이디·비밀번호를 바꿨습니다.";
    }

    /** 화면에서 그대로 보여줄 수 있도록 오류 이유를 글자로 돌려준다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(e.getReason());
    }

    private String requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object username = session == null ? null : session.getAttribute(SESSION_KEY);

        // 다른 곳에서 아이디를 바꿨으면 옛 세션은 더 쓰지 않는다.
        if (username == null || !username.equals(account.username())) {
            throw new ResponseStatusException(UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return (String) username;
    }
}
