package com.seobin.phonedrop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * 텍스트 전송 기록. 실행 비서 DB와 따로 H2 파일 DB(data/texts/)에 저장해서 재시작해도 남는다.
 * 날짜는 한국 시간 기준이다.
 * 메인 화면에서 지우면 hide(숨김, deleted_at 기록)만 하고, 관리자 페이지에서는 계속 보인다.
 * 실제로 지우는 것(delete)은 관리자만 한다.
 */
@Component
public class TextStore {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** deletedAt 은 메인 화면에서 지운 시각, 안 지웠으면 null. */
    public record TextEntry(long id, String text, LocalDateTime createdAt, LocalDateTime deletedAt) {
    }

    public record DateCount(LocalDate date, long count) {
    }

    private final JdbcClient jdbc;

    public TextStore(@Value("${texts.datasource-url:jdbc:h2:file:./data/texts/texts;DB_CLOSE_DELAY=-1}") String url) {
        this.jdbc = JdbcClient.create(new DriverManagerDataSource(url, "sa", ""));

        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS texts (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    content CLOB NOT NULL,
                    sent_date DATE NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """).update();
        jdbc.sql("ALTER TABLE texts ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP").update();
        jdbc.sql("CREATE INDEX IF NOT EXISTS texts_sent_date ON texts(sent_date)").update();
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZONE).withNano(0);
    }

    public void add(String text) {
        LocalDateTime now = now();

        jdbc.sql("INSERT INTO texts (content, sent_date, created_at) VALUES (?, ?, ?)")
                .params(text, now.toLocalDate(), now)
                .update();
    }

    /** 메인 화면용: 그 날짜에서 지우지 않은 기록, 최신순. */
    public List<TextEntry> findVisibleByDate(LocalDate date) {
        return jdbc.sql("""
                        SELECT id, content, created_at, deleted_at FROM texts
                        WHERE sent_date = ? AND deleted_at IS NULL ORDER BY id DESC
                        """)
                .param(date)
                .query(TextStore::entry)
                .list();
    }

    /** 관리자용: 그 날짜의 모든 기록(메인에서 지운 것 포함), 최신순. */
    public List<TextEntry> findByDate(LocalDate date) {
        return jdbc.sql("SELECT id, content, created_at, deleted_at FROM texts WHERE sent_date = ? ORDER BY id DESC")
                .param(date)
                .query(TextStore::entry)
                .list();
    }

    /** 기록이 있는 날짜와 개수(메인에서 지운 것 포함), 최신 날짜부터. */
    public List<DateCount> dates() {
        return jdbc.sql("SELECT sent_date, COUNT(*) AS cnt FROM texts GROUP BY sent_date ORDER BY sent_date DESC")
                .query((rs, i) -> new DateCount(rs.getDate("sent_date").toLocalDate(), rs.getLong("cnt")))
                .list();
    }

    /** 메인 화면에서 지우기: 숨기기만 한다. 이미 숨긴 것이면 false. */
    public boolean hide(long id) {
        return jdbc.sql("UPDATE texts SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")
                .params(now(), id)
                .update() > 0;
    }

    public int hideByDate(LocalDate date) {
        return jdbc.sql("UPDATE texts SET deleted_at = ? WHERE sent_date = ? AND deleted_at IS NULL")
                .params(now(), date)
                .update();
    }

    /** 관리자 영구 삭제. */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM texts WHERE id = ?").param(id).update() > 0;
    }

    public int deleteByDate(LocalDate date) {
        return jdbc.sql("DELETE FROM texts WHERE sent_date = ?").param(date).update();
    }

    private static TextEntry entry(ResultSet rs, int row) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new TextEntry(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                deletedAt == null ? null : deletedAt.toLocalDateTime());
    }
}
