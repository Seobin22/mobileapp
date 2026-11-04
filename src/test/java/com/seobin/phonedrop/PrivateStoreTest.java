package com.seobin.phonedrop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.seobin.phonedrop.privatestore.PrivateFileController;
import com.seobin.phonedrop.user.UserController;
import com.seobin.phonedrop.user.UserController.Credentials;
import com.seobin.phonedrop.user.UserSession;
import com.seobin.phonedrop.user.UserStore;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateStoreTest {

    @TempDir
    Path dir;

    private final UserStore store = new UserStore("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    private final UserSession session = new UserSession(store);
    private final UserController users = new UserController(store, session);

    private static String status(ThrowingRunnable call) {
        try {
            call.run();
            return "200";
        } catch (ResponseStatusException e) {
            return String.valueOf(e.getStatusCode().value());
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static MultipartFile[] file(String name, String content) {
        return new MultipartFile[] {
                new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8)) };
    }

    @SuppressWarnings("unchecked")
    private static List<PrivateFileController.FileInfo> files(Map<String, Object> listing) {
        return (List<PrivateFileController.FileInfo>) listing.get("files");
    }

    @Test
    void eachUserSeesOnlyOwnFilesAndGuestsAreRejected() throws Exception {
        PrivateFileController drive = new PrivateFileController(session, dir.toString());
        MockHttpServletRequest alice = new MockHttpServletRequest();
        MockHttpServletRequest bob = new MockHttpServletRequest();
        users.signup(new Credentials("alice", "password-1"), alice);
        users.signup(new Credentials("bob", "password-1"), bob);

        assertThat(status(() -> drive.list(new MockHttpServletRequest()))).isEqualTo("401");
        assertThat(status(() -> drive.upload(file("a.txt", "x"), new MockHttpServletRequest()))).isEqualTo("401");

        drive.upload(file("secret.txt", "alice data"), alice);
        assertThat(files(drive.list(alice))).extracting(PrivateFileController.FileInfo::name).containsExactly("secret.txt");
        assertThat(files(drive.list(bob))).isEmpty();
        assertThat(status(() -> drive.download("secret.txt", bob))).isEqualTo("404");
        assertThat(status(() -> drive.delete("secret.txt", bob))).isEqualTo("404");

        assertThat(drive.download("secret.txt", alice).getBody().getContentAsByteArray())
                .isEqualTo("alice data".getBytes(StandardCharsets.UTF_8));

        // 공개 업로드 폴더(uploads/)가 아니라 회원 폴더에 저장된다.
        long aliceId = session.require(alice).id();
        assertThat(Files.readString(dir.resolve(Long.toString(aliceId)).resolve("secret.txt"))).isEqualTo("alice data");

        drive.delete("secret.txt", alice);
        assertThat(files(drive.list(alice))).isEmpty();
    }

    @Test
    void rejectsPathTricks() throws Exception {
        PrivateFileController drive = new PrivateFileController(session, dir.toString());
        MockHttpServletRequest alice = new MockHttpServletRequest();
        users.signup(new Credentials("alice", "password-1"), alice);

        for (String bad : List.of("../x", "..", ".", "a/b", "a\\b", ".hidden", "", "a\nb")) {
            assertThat(status(() -> drive.download(bad, alice))).as(bad).isEqualTo("400");
            assertThat(status(() -> drive.delete(bad, alice))).as(bad).isEqualTo("400");
        }
        // 브라우저가 경로를 붙여 보내도 파일 이름만 쓴다.
        drive.upload(file("../../evil.txt", "x"), alice);
        assertThat(files(drive.list(alice))).extracting(PrivateFileController.FileInfo::name).containsExactly("evil.txt");
        assertThat(Files.exists(dir.resolve("evil.txt"))).isFalse();
    }
}
