package com.seobin.phonedrop.user;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.auth.PasswordHasher;

/**
 * 회원 계정 DB. 실행 비서·텍스트 DB와 따로 H2 파일 DB(data/users/)에 저장한다.
 * 비밀번호는 원문 없이 salt·해시·반복 횟수만 저장한다(나중에 반복 횟수를 올려도 옛 계정이 로그인된다).
 */
@Component
public class UserStore {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final HexFormat HEX = HexFormat.of();

    // 없는 아이디로 로그인해도 같은 시간이 걸리도록 대신 계산할 값.
    private static final byte[] DUMMY_SALT = PasswordHasher.newSalt();
    private static final byte[] DUMMY_HASH = PasswordHasher.hash("dummy-password", DUMMY_SALT, PasswordHasher.ITERATIONS);

    /** 다른 기능에 넘겨줄 회원 정보. 비밀번호 관련 값은 넣지 않는다. */
    public record User(long id, String username, LocalDateTime createdAt) {
    }

    /** 아이디가 이미 있을 때. */
    public static class UsernameTakenException extends RuntimeException {
    }

    private record Credentials(User user, byte[] salt, int iterations, byte[] hash) {
    }

    private final JdbcClient jdbc;

    public UserStore(@Value("${users.datasource-url:jdbc:h2:file:./data/users/users;DB_CLOSE_DELAY=-1}") String url) {
        this.jdbc = JdbcClient.create(new DriverManagerDataSource(url, "sa", ""));

        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(20) NOT NULL UNIQUE,
                    password_salt VARCHAR(64) NOT NULL,
                    password_hash VARCHAR(128) NOT NULL,
                    password_iterations INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    last_login_at TIMESTAMP
                )
                """).update();
    }

    public User create(String username, String password) {
        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash(password, salt, PasswordHasher.ITERATIONS);
        LocalDateTime now = now();

        try {
            jdbc.sql("""
                    INSERT INTO users (username, password_salt, password_hash, password_iterations, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .params(username, HEX.formatHex(salt), HEX.formatHex(hash), PasswordHasher.ITERATIONS, now)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new UsernameTakenException();
        }

        return findByUsername(username).orElseThrow().user();
    }

    /** 아이디·비밀번호가 맞으면 회원 정보를 돌려주고 마지막 로그인 시각을 남긴다. */
    public Optional<User> authenticate(String username, String password) {
        Optional<Credentials> found = findByUsername(username);

        if (found.isEmpty()) {
            PasswordHasher.matches(password, DUMMY_SALT, PasswordHasher.ITERATIONS, DUMMY_HASH);
            return Optional.empty();
        }

        Credentials credentials = found.get();
        if (!PasswordHasher.matches(password, credentials.salt(), credentials.iterations(), credentials.hash())) {
            return Optional.empty();
        }

        jdbc.sql("UPDATE users SET last_login_at = ? WHERE id = ?")
                .params(now(), credentials.user().id())
                .update();
        return Optional.of(credentials.user());
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT id, username, created_at FROM users WHERE id = ?")
                .param(id)
                .query((rs, i) -> new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .optional();
    }

    public boolean changePassword(long id, String currentPassword, String newPassword) {
        Optional<User> user = findById(id);
        if (user.isEmpty() || authenticate(user.get().username(), currentPassword).isEmpty()) {
            return false;
        }

        byte[] salt = PasswordHasher.newSalt();
        byte[] hash = PasswordHasher.hash(newPassword, salt, PasswordHasher.ITERATIONS);
        jdbc.sql("UPDATE users SET password_salt = ?, password_hash = ?, password_iterations = ? WHERE id = ?")
                .params(HEX.formatHex(salt), HEX.formatHex(hash), PasswordHasher.ITERATIONS, id)
                .update();
        return true;
    }

    private Optional<Credentials> findByUsername(String username) {
        return jdbc.sql("""
                        SELECT id, username, created_at, password_salt, password_hash, password_iterations
                        FROM users WHERE username = ?
                        """)
                .param(username)
                .query((rs, i) -> new Credentials(
                        new User(rs.getLong("id"), rs.getString("username"),
                                rs.getTimestamp("created_at").toLocalDateTime()),
                        HEX.parseHex(rs.getString("password_salt")),
                        rs.getInt("password_iterations"),
                        HEX.parseHex(rs.getString("password_hash"))))
                .optional();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZONE).withNano(0);
    }
}
