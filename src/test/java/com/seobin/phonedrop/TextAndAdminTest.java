package com.seobin.phonedrop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.admin.AdminAccount;
import com.seobin.phonedrop.admin.AdminController;
import com.seobin.phonedrop.admin.AdminController.AccountRequest;
import com.seobin.phonedrop.admin.AdminController.LoginRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class TextAndAdminTest {

    @TempDir
    Path dir;

    private static TextStore newStore() {
        return new TextStore("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private static String initialPassword(Path dir) throws Exception {
        String content = Files.readString(dir.resolve("initial_password"));
        return content.substring(content.indexOf("비밀번호: ") + "비밀번호: ".length()).trim();
    }

    @Test
    void textsAreStoredByDate() {
        TextStore store = newStore();
        store.add("첫째");
        store.add("둘째");

        LocalDate today = TextStore.today();
        assertThat(store.findByDate(today)).extracting(TextStore.TextEntry::text).containsExactly("둘째", "첫째");
        assertThat(store.findByDate(today.minusDays(1))).isEmpty();
        assertThat(store.dates()).containsExactly(new TextStore.DateCount(today, 2));

        long id = store.findByDate(today).get(0).id();
        assertThat(store.delete(id)).isTrue();
        assertThat(store.delete(id)).isFalse();
        assertThat(store.deleteByDate(today)).isEqualTo(1);
        assertThat(store.dates()).isEmpty();
    }

    @Test
    void mainPageDeleteOnlyHidesAndAdminStillSeesIt() throws Exception {
        TextStore store = newStore();
        TextController controller = new TextController(store);
        controller.addText("남길 것");
        controller.addText("지울 것");
        long id = controller.getTexts(null).get(0).id();

        controller.hideText(id);
        assertThatThrownBy(() -> controller.hideText(id)).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.getTexts(null)).extracting(TextStore.TextEntry::text).containsExactly("남길 것");

        AdminController admin = new AdminController(new AdminAccount(dir.toString()), store);
        MockHttpServletRequest request = new MockHttpServletRequest();
        admin.login(new LoginRequest("admin", initialPassword(dir)), request);
        assertThat(admin.texts(null, request))
                .extracting(TextStore.TextEntry::text, entry -> entry.deletedAt() != null)
                .containsExactly(tuple("지울 것", true), tuple("남길 것", false));

        assertThat(controller.hideTextsOfDate(TextStore.today())).isEqualTo("1개 삭제 완료");
        assertThat(controller.getTexts(null)).isEmpty();
        assertThat(admin.texts(null, request)).hasSize(2);
        assertThat(admin.dates(request)).containsExactly(new TextStore.DateCount(TextStore.today(), 2));
    }

    @Test
    void textControllerDefaultsToToday() {
        TextStore store = newStore();
        TextController controller = new TextController(store);
        controller.addText("  안녕  ");

        assertThat(controller.getTexts(null)).extracting(TextStore.TextEntry::text).containsExactly("안녕");
        assertThatThrownBy(() -> controller.addText("   ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void accountStartsWithGeneratedPasswordAndCanChange() throws Exception {
        AdminAccount account = new AdminAccount(dir.toString());
        String password = initialPassword(dir);

        assertThat(account.username()).isEqualTo("admin");
        assertThat(account.matches("admin", password)).isTrue();
        assertThat(account.matches("admin", "wrong")).isFalse();

        account.change("seobin", "new-password-1");
        assertThat(Files.exists(dir.resolve("initial_password"))).isFalse();

        AdminAccount reloaded = new AdminAccount(dir.toString());
        assertThat(reloaded.matches("seobin", "new-password-1")).isTrue();
        assertThat(reloaded.matches("admin", password)).isFalse();
    }

    @Test
    void adminApiNeedsLogin() throws Exception {
        TextStore store = newStore();
        store.add("기록");
        AdminController controller = new AdminController(new AdminAccount(dir.toString()), store);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.texts(null, request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.login(new LoginRequest("admin", "wrong"), request))
                .isInstanceOf(ResponseStatusException.class);

        controller.login(new LoginRequest("admin", initialPassword(dir)), request);
        assertThat(controller.texts(null, request)).hasSize(1);

        assertThatThrownBy(() -> controller.changeAccount(new AccountRequest("wrong", "admin", "long-enough"), request))
                .isInstanceOf(ResponseStatusException.class);
        controller.changeAccount(new AccountRequest(initialPassword(dir), "seobin", "long-enough"), request);
        assertThat(controller.me(request)).containsEntry("username", "seobin");

        controller.logout(request);
        assertThatThrownBy(() -> controller.me(request)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void loginLocksAfterRepeatedFailures() throws Exception {
        AdminController controller = new AdminController(new AdminAccount(dir.toString()), newStore());
        MockHttpServletRequest request = new MockHttpServletRequest();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> controller.login(new LoginRequest("admin", "wrong"), request))
                    .hasMessageContaining("맞지 않습니다");
        }
        assertThatThrownBy(() -> controller.login(new LoginRequest("admin", initialPassword(dir)), request))
                .hasMessageContaining("잠겼습니다");
    }
}
