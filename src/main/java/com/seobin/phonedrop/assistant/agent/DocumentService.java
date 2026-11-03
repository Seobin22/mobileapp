package com.seobin.phonedrop.assistant.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 문서 받기(빌드문서 v3.3 §4). 사용자가 올린 자료를 한 번만 읽어 청크·요약·추출(마감·요구사항·준비물)로 저장한다.
 * - 올리기: PDF, JPG/PNG, DOCX, TXT/MD, 20MB 이하. PDF 는 앞 50쪽까지.
 * - 처리: document_process turn(낮은 priority, agent worker). 글자 추출 → 스캔본·이미지는 LLM 이미지 입력(쪽당 1회)
 *   → 약 1,500자 청크 → 요약 1회(길면 청크 묶음 요약 후 합치기) → ready + document_ready turn.
 * - 파일은 data/assistant/documents/ (매일 DB 백업과 함께 백업). 파일 내용은 로그에 남기지 않는다.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    public static final String PROCESS_TRIGGER = "document_process";
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_PAGES = 50;
    static final int CHUNK_CHARS = 1500;
    static final int SUMMARY_MAX = 800;
    /** 요약 한 번에 보낼 최대 글자 수. 넘으면 묶음별 요약 후 합친다. */
    static final int SUMMARY_INPUT_MAX = 60_000;
    /** 쪽당 글자가 이보다 적으면 스캔본으로 본다. */
    static final int SCAN_CHARS_PER_PAGE = 40;

    static final String READ_SYSTEM = "너는 사용자가 올린 자료의 글자를 그대로 옮겨 적는다. 설명하지 말고 보이는 글자와 표의 내용만 적는다.";
    static final String SUMMARY_SYSTEM = """
            너는 사용자가 올린 자료(공지·안내서·시험 범위 등)를 읽고 실행 비서가 쓸 요약을 만든다.
            JSON 하나만 답한다: {"summary_md": "핵심 내용과 해야 할 것(마크다운, 800자 이내)",
            "deadlines": [{"text": "자료에 적힌 그대로", "at": "ISO-8601 또는 null"}],
            "requirements": ["제출 형식·조건 등"], "items": ["준비물"]}
            자료에 없는 내용은 지어내지 않는다.""";

    public record Document(long id, Long goalId, Long taskId, Long blockId, String filename, String mime, long sizeBytes,
                           String storagePath, String status, String error, int textChars, Integer pages,
                           String summaryMd, String extracted, String message, Instant uploadedAt, Instant processedAt) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final TurnQueue turns;
    private final ChatService chat;
    private final LlmClient llm;
    private final Path dir;

    public DocumentService(AssistantDb db, AssistantClock clock, Tx tx, Store store, TimeUtil time,
                           SettingsService settings, TurnQueue turns, ChatService chat, LlmClient llm,
                           @Value("${assistant.data-dir:data/assistant}") String dataDir) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.turns = turns;
        this.chat = chat;
        this.llm = llm;
        this.dir = Path.of(dataDir, "documents");
    }

    public Path dir() {
        return dir;
    }

    // ---------------------------------------------------------------- 올리기 (§4.2)

    /** 형식 판별: 이름 확장자 기준(앱이 보내는 content-type 은 믿지 않는다). */
    static String mimeOf(String filename) {
        String f = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (f.endsWith(".png")) {
            return "image/png";
        }
        if (f.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (f.endsWith(".txt")) {
            return "text/plain";
        }
        if (f.endsWith(".md")) {
            return "text/markdown";
        }
        return null;
    }

    /**
     * 파일을 저장하고 처리 turn 을 예약한다. fromChat 이면 채팅에 파일 카드(사용자 메시지)를 남긴다.
     * 호출하는 쪽이 트랜잭션을 연다.
     */
    public Map<String, Object> upload(String filename, byte[] data, Long goalId, Long taskId, Long blockId,
                                      String message, boolean fromChat) {
        String name = safeName(filename);
        String mime = mimeOf(name);
        if (mime == null) {
            throw ApiException.unprocessable("PDF, JPG·PNG, DOCX, TXT·MD 파일만 올릴 수 있어요");
        }
        if (data.length == 0) {
            throw ApiException.unprocessable("빈 파일입니다");
        }
        if (data.length > MAX_BYTES) {
            throw ApiException.unprocessable("파일은 20MB 이하만 올릴 수 있어요");
        }
        if (goalId != null && store.goal(goalId).isEmpty()) {
            throw ApiException.notFound("목표#" + goalId + "이 없습니다");
        }
        Instant now = clock.now();
        long id = db.insert("""
                        INSERT INTO documents(goal_id, task_id, block_id, filename, mime, size_bytes, storage_path, status,
                        message, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, '', 'processing', ?, ?)""",
                goalId, taskId, blockId, name, mime, (long) data.length, blankToNull(message), now);
        Path file = dir.resolve(id + "-" + name);
        try {
            Files.createDirectories(dir);
            Files.write(file, data);
        } catch (IOException e) {
            throw new IllegalStateException("자료를 저장할 수 없습니다", e);
        }
        db.update("UPDATE documents SET storage_path = ? WHERE id = ?", file.getFileName().toString(), id);
        Long messageId = null;
        if (fromChat) {
            messageId = db.insert("""
                            INSERT INTO messages(role, content, kind, source, document_id, created_at)
                            VALUES ('user', ?, 'reply', 'app', ?, ?)""",
                    message == null || message.isBlank() ? "자료: " + name : message.strip() + "\n(자료: " + name + ")",
                    id, now);
        }
        ObjectNode ref = Json.object();
        ref.put("document_id", id);
        long turnId = turns.enqueue(PROCESS_TRIGGER, ref, TurnQueue.PRIORITY_PULSE);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("status", "processing");
        r.put("turn_id", turnId);
        if (messageId != null) {
            r.put("message_id", messageId);
        }
        return r;
    }

    // ---------------------------------------------------------------- 처리 (§4.3)

    /** agent worker 에서 부른다(LLM 을 기다리는 동안 트랜잭션을 잡지 않는다). */
    public void process(long id) {
        Document d = tx.run(() -> find(id)).orElse(null);
        if (d == null || !"processing".equals(d.status())) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(dir.resolve(d.storagePath()));
            Extracted ex = extract(d, data);
            List<String[]> chunks = chunk(ex.pages());
            String text = String.join("\n\n", ex.pages().stream().map(p -> p[1]).toList()).strip();
            if (text.isEmpty()) {
                throw new IllegalStateException("글자를 찾지 못했습니다");
            }
            JsonNode summary = summarize(d.filename(), text);
            String summaryMd = summary.path("summary_md").asString("").strip();
            if (summaryMd.length() > SUMMARY_MAX) {
                summaryMd = summaryMd.substring(0, SUMMARY_MAX);
            }
            ObjectNode extracted = Json.object();
            extracted.set("deadlines", summary.path("deadlines").isArray() ? summary.get("deadlines") : Json.array());
            extracted.set("requirements", summary.path("requirements").isArray() ? summary.get("requirements") : Json.array());
            extracted.set("items", summary.path("items").isArray() ? summary.get("items") : Json.array());
            if (ex.truncated()) {
                extracted.put("note", "앞 " + MAX_PAGES + "쪽만 읽음");
            }
            final String sm = summaryMd;
            tx.exec(() -> {
                db.update("DELETE FROM document_chunks WHERE document_id = ?", id);
                for (int i = 0; i < chunks.size(); i++) {
                    String[] c = chunks.get(i);
                    db.insert("INSERT INTO document_chunks(document_id, idx, page, text) VALUES (?, ?, ?, ?)",
                            id, i, c[0] == null ? null : Integer.valueOf(c[0]), c[1]);
                }
                String extractedJson = Json.write(extracted);
                db.update("""
                                UPDATE documents SET status = 'ready', error = NULL, text_chars = ?, pages = ?, summary_md = ?,
                                extracted = ?, processed_at = ? WHERE id = ?""",
                        text.length(), ex.pageCount(), sm,
                        extractedJson.length() > 19000 ? extractedJson.substring(0, 19000) : extractedJson, clock.now(), id);
                ObjectNode ref = Json.object();
                ref.put("document_id", id);
                turns.enqueue("document_ready", ref, TurnQueue.PRIORITY_DEFAULT);
                if (ex.truncated()) {
                    chat.sendAssistant("'" + d.filename() + "'은 " + ex.pageCount() + "쪽이라 앞 " + MAX_PAGES
                            + "쪽만 읽었어요", "system", "normal", null, null, true);
                }
            });
            log.info("document {} ready: chars={} chunks={}", id, text.length(), chunks.size());
        } catch (IOException | RuntimeException e) {
            log.warn("document {} failed: {}", id, e.getClass().getSimpleName() + ": " + e.getMessage());
            String reason = e instanceof LlmClient.LlmException || e instanceof ConfiguredLlm.NotConfiguredException
                    ? "읽는 모델 호출 실패: " + e.getMessage() : e.getMessage();
            tx.exec(() -> {
                db.update("UPDATE documents SET status = 'failed', error = ?, processed_at = ? WHERE id = ?",
                        reason == null ? "알 수 없는 오류" : reason.substring(0, Math.min(reason.length(), 900)),
                        clock.now(), id);
                long mid = chat.sendAssistant("자료를 읽지 못했어요 (" + d.filename() + ")", "system", "normal", null,
                        null, false);
                db.update("UPDATE messages SET document_id = ? WHERE id = ?", id, mid);
            });
        }
    }

    /** 쪽 목록 [page 번호 또는 null, 글자]. */
    record Extracted(List<String[]> pages, int pageCount, boolean truncated) {
    }

    Extracted extract(Document d, byte[] data) throws IOException {
        String mime = d.mime();
        if (mime.startsWith("text/")) {
            return new Extracted(List.<String[]>of(new String[]{null, new String(data, StandardCharsets.UTF_8)}), 1, false);
        }
        if (mime.startsWith("image/")) {
            String text = readImages(List.of(new LlmClient.Image(mime, data)));
            return new Extracted(List.<String[]>of(new String[]{null, text}), 1, false);
        }
        if (mime.equals("application/pdf")) {
            return extractPdf(data);
        }
        return new Extracted(List.<String[]>of(new String[]{null, docxText(data)}), 1, false);
    }

    private Extracted extractPdf(byte[] data) throws IOException {
        List<String[]> pages = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(data))) {
            int total = pdf.getNumberOfPages();
            int n = Math.min(total, MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            int chars = 0;
            for (int p = 1; p <= n; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String t = stripper.getText(pdf).strip();
                chars += t.length();
                pages.add(new String[]{String.valueOf(p), t});
            }
            if (chars < SCAN_CHARS_PER_PAGE * n) {
                // 스캔본: 쪽마다 이미지로 읽는다(쪽당 LLM 1회).
                PDFRenderer renderer = new PDFRenderer(pdf);
                pages.clear();
                for (int p = 0; p < n; p++) {
                    var image = renderer.renderImageWithDPI(p, 110, ImageType.RGB);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image, "jpg", out);
                    pages.add(new String[]{String.valueOf(p + 1),
                            readImages(List.of(new LlmClient.Image("image/jpeg", out.toByteArray())))});
                }
            }
            return new Extracted(pages, total, total > MAX_PAGES);
        }
    }

    /** DOCX 본문: word/document.xml 의 글자(문단마다 줄바꿈). 의존성 없이 zip·태그만 벗긴다. */
    static String docxText(byte[] data) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    String xml = new String(readLimited(zip, 30L * 1024 * 1024), StandardCharsets.UTF_8);
                    String text = xml.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<w:br/>", "\n")
                            .replaceAll("<[^>]+>", "");
                    return unescape(text).replaceAll("\n{3,}", "\n\n").strip();
                }
            }
        }
        throw new IOException("DOCX 본문을 찾지 못했습니다");
    }

    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException("DOCX 본문이 너무 큽니다");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private String readImages(List<LlmClient.Image> images) {
        return onceLlm(READ_SYSTEM, "이 이미지의 글자와 내용을 그대로 적어 주세요.", images).text().strip();
    }

    private LlmClient.LlmResponse onceLlm(String system, String text, List<LlmClient.Image> images) {
        String model = settings.getString("documents.model");
        if (llm instanceof ConfiguredLlm configured) {
            return configured.once(system, text, images, model);
        }
        return llm.once(system, text, images);
    }

    /** 약 1,500자 단위. 쪽 경계는 넘지 않는다(쪽 번호를 지키려고). */
    static List<String[]> chunk(List<String[]> pages) {
        List<String[]> out = new ArrayList<>();
        for (String[] p : pages) {
            String t = p[1] == null ? "" : p[1].strip();
            int i = 0;
            while (i < t.length()) {
                int end = Math.min(t.length(), i + CHUNK_CHARS);
                if (end < t.length()) {
                    int nl = t.lastIndexOf('\n', end);
                    if (nl > i + CHUNK_CHARS / 2) {
                        end = nl;
                    }
                }
                String c = t.substring(i, end).strip();
                if (!c.isEmpty()) {
                    out.add(new String[]{p[0], c});
                }
                i = end;
            }
        }
        return out;
    }

    /** 요약 1회. 길면 묶음별 요약을 모아 한 번 더 요약한다. */
    private JsonNode summarize(String filename, String text) {
        String input = text;
        if (text.length() > SUMMARY_INPUT_MAX) {
            StringBuilder partial = new StringBuilder();
            for (int i = 0; i < text.length(); i += SUMMARY_INPUT_MAX) {
                String part = text.substring(i, Math.min(text.length(), i + SUMMARY_INPUT_MAX));
                partial.append(onceLlm(SUMMARY_SYSTEM, "자료 '" + filename + "'의 일부:\n\n" + part, List.of()).text())
                        .append("\n\n");
            }
            input = "자료의 부분별 요약(JSON)들:\n\n" + partial;
        }
        String answer = onceLlm(SUMMARY_SYSTEM, "자료 '" + filename + "':\n\n" + input, List.of()).text();
        return parseJson(answer);
    }

    static JsonNode parseJson(String answer) {
        String a = answer == null ? "" : answer;
        int s = a.indexOf('{');
        int e = a.lastIndexOf('}');
        if (s >= 0 && e > s) {
            try {
                return Json.parse(a.substring(s, e + 1));
            } catch (RuntimeException ignored) {
                // 아래에서 글 전체를 요약으로 쓴다.
            }
        }
        ObjectNode o = Json.object();
        o.put("summary_md", a.strip());
        return o;
    }

    // ---------------------------------------------------------------- 조회·연결·삭제

    public Optional<Document> find(long id) {
        return db.jdbc().sql("SELECT * FROM documents WHERE id = ?").param(id).query(DocumentService::map).optional();
    }

    public List<Document> ofGoal(long goalId) {
        return db.jdbc().sql("SELECT * FROM documents WHERE goal_id = ? ORDER BY id DESC").param(goalId)
                .query(DocumentService::map).list();
    }

    public List<Document> all(int limit) {
        return db.jdbc().sql("SELECT * FROM documents ORDER BY id DESC LIMIT ?").param(limit)
                .query(DocumentService::map).list();
    }

    /** read_document(§4.5): query 단어가 많이 들어간 청크 상위 3개, 또는 해당 쪽, 둘 다 없으면 앞 3개. */
    public List<Map<String, Object>> read(long id, String query, Integer page) {
        List<String[]> chunks = db.jdbc().sql("SELECT page, text FROM document_chunks WHERE document_id = ? ORDER BY idx")
                .param(id).query((rs, n) -> new String[]{rs.getObject(1) == null ? null : String.valueOf(rs.getInt(1)),
                        rs.getString(2)}).list();
        List<String[]> picked;
        if (page != null) {
            picked = chunks.stream().filter(c -> String.valueOf(page).equals(c[0])).toList();
        } else if (query != null && !query.isBlank()) {
            List<String> words = List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
            picked = chunks.stream()
                    .map(c -> Map.entry(c, score(c[1], words)))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<String[], Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(3).map(Map.Entry::getKey).toList();
        } else {
            picked = chunks.stream().limit(3).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] c : picked) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", c[0] == null ? null : Integer.valueOf(c[0]));
            m.put("text", c[1]);
            out.add(m);
        }
        return out;
    }

    static int score(String text, List<String> words) {
        String t = text.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String w : words) {
            if (w.isBlank()) {
                continue;
            }
            for (int i = t.indexOf(w); i >= 0; i = t.indexOf(w, i + w.length())) {
                n++;
            }
        }
        return n;
    }

    public void link(long id, Long goalId, Long taskId) {
        if (find(id).isEmpty()) {
            throw ApiException.notFound("자료#" + id + "이 없습니다");
        }
        if (goalId != null && store.goal(goalId).isEmpty()) {
            throw ApiException.notFound("목표#" + goalId + "이 없습니다");
        }
        Long goal = goalId;
        if (goal == null && taskId != null) {
            goal = store.task(taskId).orElseThrow(() -> ApiException.notFound("할일#" + taskId + "이 없습니다")).goalId();
        }
        db.update("UPDATE documents SET goal_id = COALESCE(?, goal_id), task_id = COALESCE(?, task_id) WHERE id = ?",
                goal, taskId, id);
    }

    /** 파일·청크·요약 모두 지운다(§4.6). 채팅 파일 카드는 이름만 남는다. */
    public void delete(long id) {
        Document d = find(id).orElseThrow(() -> ApiException.notFound("자료#" + id + "이 없습니다"));
        db.update("DELETE FROM document_chunks WHERE document_id = ?", id);
        db.update("UPDATE messages SET document_id = NULL WHERE document_id = ?", id);
        db.update("DELETE FROM documents WHERE id = ?", id);
        try {
            if (!d.storagePath().isBlank()) {
                Files.deleteIfExists(dir.resolve(d.storagePath()));
            }
        } catch (IOException e) {
            log.warn("document {} file delete failed", id);
        }
    }

    public byte[] fileBytes(Document d) throws IOException {
        return Files.readAllBytes(dir.resolve(d.storagePath()));
    }

    public Map<String, Object> toMap(Document d, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("goal_id", d.goalId());
        m.put("task_id", d.taskId());
        m.put("filename", d.filename());
        m.put("mime", d.mime());
        m.put("size_bytes", d.sizeBytes());
        m.put("status", d.status());
        m.put("error", d.error());
        m.put("pages", d.pages());
        m.put("uploaded_at", time.iso(d.uploadedAt()));
        m.put("processed_at", time.iso(d.processedAt()));
        m.put("summary_md", d.summaryMd());
        if (detail) {
            m.put("extracted", d.extracted() == null ? null : Json.parse(d.extracted()));
            m.put("text_chars", d.textChars());
        }
        return m;
    }

    // ---------------------------------------------------------------- 맥락 (§4.5)

    /**
     * 목표 관련 turn 이면 그 목표 문서의 요약(문서당 800자, 최근 3개), 그 밖에는 제목 목록만. document_ready 는 그 문서 요약·추출.
     */
    public String contextSection(String trigger, Long goalId, Long documentId) {
        StringBuilder sb = new StringBuilder();
        if (documentId != null) {
            find(documentId).ifPresent(d -> sb.append("- 새 자료#").append(d.id()).append(' ').append(d.filename())
                    .append(d.goalId() == null ? " (목표 미연결)" : " (목표#" + d.goalId() + ")").append('\n')
                    .append("  요약: ").append(d.summaryMd().replace("\n", "\n  ")).append('\n')
                    .append("  추출: ").append(d.extracted() == null ? "{}" : d.extracted()).append('\n'));
        }
        if (goalId != null) {
            ofGoal(goalId).stream().filter(d -> "ready".equals(d.status()) && d.id() != (documentId == null ? -1L : documentId))
                    .limit(3)
                    .forEach(d -> sb.append("- 자료#").append(d.id()).append(' ').append(d.filename()).append(" (목표#")
                            .append(goalId).append(") 요약: ").append(d.summaryMd().replace("\n", " ")).append('\n'));
        }
        List<Document> list = all(20).stream().filter(d -> "ready".equals(d.status())).toList();
        if (!list.isEmpty()) {
            sb.append("- 올린 자료 목록: ").append(String.join(", ", list.stream()
                    .map(d -> "자료#" + d.id() + " " + d.filename() + (d.goalId() == null ? "" : "(목표#" + d.goalId() + ")"))
                    .toList())).append('\n');
        }
        return sb.isEmpty() ? "없음\n" : sb.toString();
    }

    static Document map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Document(rs.getLong("id"), AssistantDb.nullableLong(rs, "goal_id"), AssistantDb.nullableLong(rs, "task_id"),
                AssistantDb.nullableLong(rs, "block_id"), rs.getString("filename"), rs.getString("mime"),
                rs.getLong("size_bytes"), rs.getString("storage_path"), rs.getString("status"), rs.getString("error"),
                rs.getInt("text_chars"), AssistantDb.nullableInt(rs, "pages"), rs.getString("summary_md"),
                rs.getString("extracted"), rs.getString("message"), AssistantDb.instant(rs, "uploaded_at"),
                AssistantDb.instant(rs, "processed_at"));
    }

    private static String safeName(String filename) {
        String n = filename == null || filename.isBlank() ? "file" : filename.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\r\\n\\t/\\\\:*?\"<>|]", "_");
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
