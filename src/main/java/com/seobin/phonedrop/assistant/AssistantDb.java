package com.seobin.phonedrop.assistant;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

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
            "assistant/db/V4__negotiation_proactive.sql"
    );

    private final JdbcClient jdbc;

    public AssistantDb(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        migrate(dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
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
