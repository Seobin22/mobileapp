package com.seobin.phonedrop.user;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.user.UserStore.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 지금 로그인한 회원. 로그인이 필요한 기능은 컨트롤러에서 이렇게 쓴다:
 * <pre>
 *     User user = userSession.require(request);   // 로그인 안 했으면 401
 * </pre>
 * 세션 쿠키는 접속한 호스트(seobin.page)에만 붙는다.
 */
@Component
public class UserSession {

    private static final String SESSION_KEY = "userId";

    private final UserStore store;

    public UserSession(UserStore store) {
        this.store = store;
    }

    public Optional<User> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(SESSION_KEY);
        return id instanceof Long userId ? store.findById(userId) : Optional.empty();
    }

    public User require(HttpServletRequest request) {
        return current(request)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "로그인이 필요합니다."));
    }

    void login(HttpServletRequest request, User user) {
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_KEY, user.id());
    }

    void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
