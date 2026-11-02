package com.seobin.phonedrop.user;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.user.UserStore.User;
import com.seobin.phonedrop.user.UserStore.UsernameTakenException;

import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** 회원가입·로그인 API (seobin.page/account.html). */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** 아이디: 영문 소문자로 시작, 소문자·숫자·밑줄 3~20자. 대문자로 입력해도 소문자로 저장한다. */
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9_]{2,19}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 100;

    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_LOCK_SECONDS = 300;
    private static final int MAX_SIGNUPS_PER_HOUR = 30;

    public record Credentials(String username, String password) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    private record Failures(int count, Instant lockedUntil) {
    }

    private final UserStore store;
    private final UserSession session;

    // 아이디별 로그인 실패 횟수. 서버를 재시작하면 초기화된다.
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    // 최근 1시간 회원가입 시각. 계정을 대량으로 만드는 것을 막는다.
    private final Deque<Instant> recentSignups = new ArrayDeque<>();

    public UserController(UserStore store, UserSession session) {
        this.store = store;
        this.session = session;
    }

    @PostMapping("/signup")
    public Map<String, String> signup(@RequestBody Credentials body, HttpServletRequest request) {
        String username = normalize(body.username());

        if (!USERNAME.matcher(username).matches()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "아이디는 영문 소문자로 시작하고 소문자·숫자·밑줄(_)만 써서 3~20자로 만들어 주세요.");
        }
        checkPassword(body.password());
        takeSignupSlot();

        User user;
        try {
            user = store.create(username, body.password());
        } catch (UsernameTakenException e) {
            throw new ResponseStatusException(CONFLICT, "이미 있는 아이디입니다.");
        }

        session.login(request, user);
        return Map.of("username", user.username());
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Credentials body, HttpServletRequest request) {
        String username = normalize(body.username());
        Failures previous = failures.get(username);

        if (previous != null && Instant.now().isBefore(previous.lockedUntil())) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "로그인 실패가 많아 5분 동안 잠겼습니다.");
        }

        User user = store.authenticate(username, body.password() == null ? "" : body.password())
                .orElse(null);

        if (user == null) {
            if (failures.size() > 10_000) {
                failures.clear();
            }
            failures.merge(username, new Failures(1, Instant.EPOCH), (old, one) -> old.count() + 1 >= MAX_LOGIN_FAILURES
                    ? new Failures(0, Instant.now().plusSeconds(LOGIN_LOCK_SECONDS))
                    : new Failures(old.count() + 1, Instant.EPOCH));
            throw new ResponseStatusException(UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않습니다.");
        }

        failures.remove(username);
        session.login(request, user);
        return Map.of("username", user.username());
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        session.logout(request);
        return "로그아웃 완료";
    }

    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        User user = session.require(request);
        return Map.of("username", user.username(), "createdAt", user.createdAt().toString());
    }

    @PostMapping("/password")
    public String changePassword(@RequestBody PasswordChange body, HttpServletRequest request) {
        User user = session.require(request);
        checkPassword(body.newPassword());

        if (body.currentPassword() == null
                || !store.changePassword(user.id(), body.currentPassword(), body.newPassword())) {
            throw new ResponseStatusException(BAD_REQUEST, "현재 비밀번호가 맞지 않습니다.");
        }
        return "비밀번호를 바꿨습니다.";
    }

    /** 화면에서 그대로 보여줄 수 있도록 오류 이유를 글자로 돌려준다. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(e.getReason());
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "비밀번호는 8~100자로 만들어 주세요.");
        }
    }

    private synchronized void takeSignupSlot() {
        Instant hourAgo = Instant.now().minusSeconds(3600);
        while (!recentSignups.isEmpty() && recentSignups.peekFirst().isBefore(hourAgo)) {
            recentSignups.pollFirst();
        }
        if (recentSignups.size() >= MAX_SIGNUPS_PER_HOUR) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "지금은 회원가입이 많습니다. 잠시 뒤 다시 시도해 주세요.");
        }
        recentSignups.addLast(Instant.now());
    }
}
