package com.seobin.phonedrop.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * DB 접근 진입점. 생성 시 마이그레이션(assistant/db/V{n}__*.sql)을 순서대로 적용한다.
 * Alembic 대신 의존성 없이 버전 번호만 관리한다.
 */
@Component
public class AssistantDb {

    /** 적용할 스크립트. 새 버전은 끝에 추가만 한다. */
    private static final List<String> MIGRATIONS = List.of(
            "assistant/db/V1__init.sql",
            "assistant/db/V2__chat_agent.sql",
            "assistant/db/V3__turn_cache_tokens.sql",
            "assistant/db/V4__negotiation_proactive.sql",
            "assistant/db/V5__fixed_blocks_goals.sql"
    );

    private static final Logger log = LoggerFactory.getLogger(AssistantDb.class);

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final Path backupDir;

    public AssistantDb(DataSource dataSource, @Value("${assistant.data-dir:data/assistant}") String dataDir) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dataSource = dataSource;
        this.backupDir = Path.of(dataDir, "backups");
        migrate(dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * 파일 DB 이면 H2 BACKUP TO 로 zip 백업을 만든다(온라인 백업, 앱을 멈추지 않는다). 메모리 DB(테스트)는 null.
     * 파일 이름: {prefix}-{yyyyMMdd-HHmmss}.zip, 위치: {data-dir}/backups/
     */
    public Path backup(String prefix) {
        try (Connection conn = dataSource.getConnection()) {
            return backup(conn, prefix);
        } catch (SQLException e) {
            throw new IllegalStateException("백업 실패", e);
        }
    }

    private Path backup(Connection conn, String prefix) throws SQLException {
        String url = conn.getMetaData().getURL();
        if (url == null || !url.startsWith("jdbc:h2:file:")) {
            return null;
        }
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new IllegalStateException("백업 폴더를 만들 수 없습니다: " + backupDir, e);
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = backupDir.resolve(prefix + "-" + stamp + ".zip").toAbsolutePath();
        try (var st = conn.createStatement()) {
            st.execute("BACKUP TO '" + file.toString().replace("'", "''") + "'");
        }
        log.info("assistant DB backup: {}", file);
        return file;
    }

    public Path backupDir() {
        return backupDir;
    }

    private void migrate(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS schema_version(version INT NOT NULL)");
            int current;
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
                rs.next();
                current = rs.getInt(1);
            }
            if (current > 0 && current < MIGRATIONS.size()) {
                // 스키마를 바꾸기 전에 전체 백업(빌드문서 v3.1 §2.1). 실패하면 마이그레이션하지 않는다.
                backup(conn, "pre-V" + (current + 1));
            }
            for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource(MIGRATIONS.get(v - 1)));
                conn.createStatement().execute("INSERT INTO schema_version(version) VALUES (" + v + ")");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("assistant DB migration failed", e);
        }
    }

    /** INSERT 후 생성된 id 를 돌려준다. Instant 인자는 자동으로 UTC 시각으로 바꾼다. */
    public long insert(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Object p : params) {
            spec = spec.param(p instanceof Instant t ? ts(t) : p);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        spec.update(keys, "id");
        return keys.getKeyAs(Long.class);
    }

    /** UPDATE/DELETE. Instant 인자는 자동으로 UTC 시각으로 바꾼다. */
    public int update(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Object p : params) {
            spec = spec.param(p instanceof Instant t ? ts(t) : p);
        }
        return spec.update();
    }

    public static OffsetDateTime ts(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
