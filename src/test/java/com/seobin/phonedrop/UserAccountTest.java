package com.seobin.phonedrop;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.user.UserController;
import com.seobin.phonedrop.user.UserController.Credentials;
import com.seobin.phonedrop.user.UserController.PasswordChange;
import com.seobin.phonedrop.user.UserSession;
import com.seobin.phonedrop.user.UserStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountTest {

    private final UserStore store = new UserStore("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private final UserSession session = new UserSession(store);
    private final UserController controller = new UserController(store, session);

    private static String status(Runnable call) {
        try {
            call.run();
            return "200";
        } catch (ResponseStatusException e) {
            return String.valueOf(e.getStatusCode().value());
        }
    }

    @Test
    void signupLogsInAndNormalizesUsername() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(controller.signup(new Credentials("  Seobin ", "password-1"), request))
                .containsEntry("username", "seobin");
        assertThat(session.require(request).username()).isEqualTo("seobin");
        assertThat(controller.me(request)).containsEntry("username", "seobin");
    }

    @Test
    void signupRejectsBadInputAndDuplicates() {
        controller.signup(new Credentials("seobin", "password-1"), new MockHttpServletRequest());

        assertThat(status(() -> controller.signup(new Credentials("seobin", "password-2"), new MockHttpServletRequest())))
                .isEqualTo("409");
        assertThat(status(() -> controller.signup(new Credentials("ab", "password-1"), new MockHttpServletRequest())))
                .isEqualTo("400");
        assertThat(status(() -> controller.signup(new Credentials("1abc", "password-1"), new MockHttpServletRequest())))
                .isEqualTo("400");
        assertThat(status(() -> controller.signup(new Credentials("new_user", "short"), new MockHttpServletRequest())))
                .isEqualTo("400");
    }

    @Test
    void loginLogoutAndPasswordChange() {
        controller.signup(new Credentials("seobin", "password-1"), new MockHttpServletRequest());
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(status(() -> controller.me(request))).isEqualTo("401");
        assertThat(status(() -> controller.login(new Credentials("seobin", "wrong-pass"), request))).isEqualTo("401");
        assertThat(status(() -> controller.login(new Credentials("nobody", "password-1"), request))).isEqualTo("401");

        controller.login(new Credentials("SEOBIN", "password-1"), request);
        assertThat(controller.me(request)).containsEntry("username", "seobin");

        assertThat(status(() -> controller.changePassword(new PasswordChange("wrong-pass", "password-2"), request)))
                .isEqualTo("400");
        controller.changePassword(new PasswordChange("password-1", "password-2"), request);

        controller.logout(request);
        assertThat(status(() -> controller.me(request))).isEqualTo("401");
        assertThat(store.authenticate("seobin", "password-1")).isEmpty();
        assertThat(store.authenticate("seobin", "password-2")).isPresent();
    }

    @Test
    void loginLocksUsernameAfterRepeatedFailures() {
        controller.signup(new Credentials("seobin", "password-1"), new MockHttpServletRequest());
        MockHttpServletRequest request = new MockHttpServletRequest();

        for (int i = 0; i < 5; i++) {
            assertThat(status(() -> controller.login(new Credentials("seobin", "wrong-pass"), request))).isEqualTo("401");
        }
        assertThat(status(() -> controller.login(new Credentials("seobin", "password-1"), request))).isEqualTo("429");
    }
}
