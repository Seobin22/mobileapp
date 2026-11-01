package com.seobin.phonedrop.check;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub Actions 결과물(Artifact) 목록을 읽기 전용으로 가져온다.
 * 토큰은 선택: 환경변수 CHECK_GITHUB_TOKEN 또는 data/check/github_token 파일.
 * 토큰이 없으면 목록만 보이고, 다운로드는 GitHub 화면 링크로 대신한다.
 */
@Service
public class ArtifactService {

    static final String REPO = "Seobin22/mobileapp";
    private static final String API = "https://api.github.com/repos/" + REPO + "/actions/artifacts";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Path TOKEN_FILE = Path.of("data", "check", "github_token");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private Map<String, Object> cached;
    private Instant cachedAt = Instant.EPOCH;

    public record Artifact(long id, String name, long size, String createdAt, String date,
                           boolean expired, long runId, String pageUrl) {
    }

    public synchronized Map<String, Object> list() throws IOException, InterruptedException {
        if (cached != null && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
            return cached;
        }
        HttpResponse<String> res = http.send(request(API + "?per_page=100").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("GitHub API " + res.statusCode());
        }
        JsonNode root = mapper.readTree(res.body());

        Map<String, List<Artifact>> byDate = new LinkedHashMap<>();
        for (JsonNode a : root.path("artifacts")) {
            Instant created = Instant.parse(a.path("created_at").asString());
            String date = created.atZone(KST).toLocalDate().toString();
            long runId = a.path("workflow_run").path("id").asLong();
            long id = a.path("id").asLong();
            Artifact art = new Artifact(id, a.path("name").asString(), a.path("size_in_bytes").asLong(),
                    created.toString(), date, a.path("expired").asBoolean(), runId,
                    "https://github.com/" + REPO + "/actions/runs/" + runId + "/artifacts/" + id);
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(art);
        }
        // GitHub API 는 최신순으로 주므로 날짜 순서도 최신순이다.
        List<Map<String, Object>> groups = new ArrayList<>();
        byDate.forEach((date, items) -> groups.add(Map.of("date", date, "artifacts", items)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", REPO);
        result.put("total", root.path("total_count").asLong());
        result.put("directDownload", token().isPresent());
        result.put("groups", groups);
        cached = result;
        cachedAt = Instant.now();
        return result;
    }

    /** 토큰이 있으면 GitHub 가 주는 짧은 수명의 zip 주소를 돌려준다. */
    public Optional<String> downloadUrl(long id) throws IOException, InterruptedException {
        if (token().isEmpty()) {
            return Optional.empty();
        }
        HttpResponse<Void> res = http.send(request(API + "/" + id + "/zip").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() / 100 == 3) {
            return res.headers().firstValue("Location");
        }
        return Optional.empty();
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "phonedrop-check");
        token().ifPresent(t -> b.header("Authorization", "Bearer " + t));
        return b;
    }

    private Optional<String> token() {
        String env = System.getenv("CHECK_GITHUB_TOKEN");
        if (env != null && !env.isBlank()) {
            return Optional.of(env.trim());
        }
        try {
            if (Files.isRegularFile(TOKEN_FILE)) {
                String t = Files.readString(TOKEN_FILE).trim();
                if (!t.isEmpty()) {
                    return Optional.of(t);
                }
            }
        } catch (IOException ignored) {
            // 토큰 파일을 못 읽으면 토큰 없이 동작한다.
        }
        return Optional.empty();
    }
}
