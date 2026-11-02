package com.seobin.phonedrop.admin;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 관리자 아이디·비밀번호. data/admin/account 에 비밀번호 해시(PBKDF2)만 저장한다.
 * 파일이 없으면 아이디 admin 과 무작위 비밀번호를 만들고, 그 비밀번호를 data/admin/initial_password 에 적는다.
 * 관리자 페이지에서 비밀번호를 바꾸면 initial_password 는 지운다.
 */
@Component
public class AdminAccount {

    private static final Logger log = LoggerFactory.getLogger(AdminAccount.class);
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path accountFile;
    private final Path initialPasswordFile;

    private String username;
    private byte[] salt;
    private byte[] hash;

    public AdminAccount(@Value("${admin.data-dir:data/admin}") String dataDir) throws IOException {
        this.accountFile = Path.of(dataDir, "account");
        this.initialPasswordFile = Path.of(dataDir, "initial_password");

        if (Files.exists(accountFile)) {
            load();
        } else {
            String password = randomPassword();
            set("admin", password);
            writePrivate(initialPasswordFile, "아이디: admin\n비밀번호: " + password + "\n");
            log.info("관리자 계정을 새로 만들었습니다. 처음 비밀번호: {}", initialPasswordFile.toAbsolutePath());
        }
    }

    public synchronized String username() {
        return username;
    }

    public synchronized boolean matches(String username, String password) {
        boolean sameName = MessageDigest.isEqual(
                this.username.getBytes(StandardCharsets.UTF_8),
                username.getBytes(StandardCharsets.UTF_8));
        boolean samePassword = MessageDigest.isEqual(hash, derive(password, salt));
        return sameName & samePassword;
    }

    public synchronized void change(String newUsername, String newPassword) throws IOException {
        set(newUsername, newPassword);
        Files.deleteIfExists(initialPasswordFile);
    }

    private void set(String newUsername, String newPassword) throws IOException {
        byte[] newSalt = new byte[16];
        RANDOM.nextBytes(newSalt);
        byte[] newHash = derive(newPassword, newSalt);

        Properties props = new Properties();
        props.setProperty("username", newUsername);
        props.setProperty("salt", HexFormat.of().formatHex(newSalt));
        props.setProperty("hash", HexFormat.of().formatHex(newHash));

        Files.createDirectories(accountFile.getParent());
        Path temp = accountFile.resolveSibling("account.tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }
        restrict(temp);
        Files.move(temp, accountFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        this.username = newUsername;
        this.salt = newSalt;
        this.hash = newHash;
    }

    private void load() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(accountFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        this.username = props.getProperty("username");
        this.salt = HexFormat.of().parseHex(props.getProperty("salt"));
        this.hash = HexFormat.of().parseHex(props.getProperty("hash"));
    }

    private static byte[] derive(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void writePrivate(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            restrict(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)에서는 권한 설정을 건너뛴다.
        }
    }
}
