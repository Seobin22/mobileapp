package com.seobin.phonedrop;

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
 */
@Component
public class TextStore {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record TextEntry(long id, String text, LocalDateTime createdAt) {
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
        jdbc.sql("CREATE INDEX IF NOT EXISTS texts_sent_date ON texts(sent_date)").update();
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public void add(String text) {
        LocalDateTime now = LocalDateTime.now(ZONE).withNano(0);

        jdbc.sql("INSERT INTO texts (content, sent_date, created_at) VALUES (?, ?, ?)")
                .params(text, now.toLocalDate(), now)
                .update();
    }

    /** 그 날짜의 기록, 최신순. */
    public List<TextEntry> findByDate(LocalDate date) {
        return jdbc.sql("SELECT id, content, created_at FROM texts WHERE sent_date = ? ORDER BY id DESC")
                .param(date)
                .query((rs, i) -> new TextEntry(
                        rs.getLong("id"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toLocalDateTime()))
                .list();
    }

    /** 기록이 있는 날짜와 개수, 최신 날짜부터. */
    public List<DateCount> dates() {
        return jdbc.sql("SELECT sent_date, COUNT(*) AS cnt FROM texts GROUP BY sent_date ORDER BY sent_date DESC")
                .query((rs, i) -> new DateCount(rs.getDate("sent_date").toLocalDate(), rs.getLong("cnt")))
                .list();
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM texts WHERE id = ?").param(id).update() > 0;
    }

    public int deleteByDate(LocalDate date) {
        return jdbc.sql("DELETE FROM texts WHERE sent_date = ?").param(date).update();
    }
}
