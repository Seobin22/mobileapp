package com.seobin.phonedrop.assistant.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;

/**
 * 카카오 REST API(빌드문서 v3.3 §3, 엔드포인트는 DECISIONS.md).
 * - 장소: dapi.kakao.com/v2/local/search/keyword.json, address.json (카카오맵 사용 설정 필요)
 * - 대중교통·도보: dapi.kakao.com/v2/routing/publictraffic, /v2/routing/walk (카카오맵 사용 설정 필요)
 * - 자동차: apis-navi.kakaomobility.com/v1/directions (카카오내비/모빌리티)
 * 키는 환경변수 KAKAO_REST_API_KEY 또는 관리 화면이 저장한 data/assistant/kakao_api_key. 키 값은 로그·응답에 남기지 않는다.
 */
@Component
public class KakaoMapApi implements MapApi {

    private static final Logger log = LoggerFactory.getLogger(KakaoMapApi.class);
    static final String LOCAL = "https://dapi.kakao.com";
    static final String NAVI = "https://apis-navi.kakaomobility.com";

    private final Path keyFile;
    private final String envKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String status = "unknown";

    public KakaoMapApi(@Value("${assistant.data-dir:data/assistant}") String dataDir,
                       @Value("${KAKAO_REST_API_KEY:}") String envKey) {
        this.keyFile = Path.of(dataDir, "kakao_api_key");
        this.envKey = envKey == null ? "" : envKey.trim();
    }

    private String key() {
        if (!envKey.isEmpty()) {
            return envKey;
        }
        try {
            return Files.exists(keyFile) ? Files.readString(keyFile).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    public void saveKey(String key) throws IOException {
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, key.trim() + "\n");
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)
        }
        status = "unknown";
    }

    @Override
    public boolean hasKey() {
        return !key().isEmpty();
    }

    @Override
    public String status() {
        return hasKey() ? status : "no_key";
    }

    @Override
    public Optional<Place> searchKeyword(String query, Double nearLat, Double nearLng) {
        String url = LOCAL + "/v2/local/search/keyword.json?size=1&query=" + enc(query)
                + (nearLat != null && nearLng != null ? "&x=" + num(nearLng) + "&y=" + num(nearLat) + "&radius=20000" : "");
        JsonNode docs = get(url, true).path("documents");
        if ((docs.isEmpty()) && nearLat != null) {
            docs = get(LOCAL + "/v2/local/search/keyword.json?size=1&query=" + enc(query), true).path("documents");
        }
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        JsonNode d = docs.get(0);
        String address = d.path("road_address_name").asString("");
        return Optional.of(new Place(d.path("place_name").asString(query),
                address.isBlank() ? d.path("address_name").asString("") : address,
                Double.parseDouble(d.path("y").asString("0")), Double.parseDouble(d.path("x").asString("0"))));
    }

    @Override
    public Optional<Place> searchAddress(String query) {
        JsonNode docs = get(LOCAL + "/v2/local/search/address.json?size=1&query=" + enc(query), true).path("documents");
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        JsonNode d = docs.get(0);
        return Optional.of(new Place(d.path("address_name").asString(query), d.path("address_name").asString(""),
                Double.parseDouble(d.path("y").asString("0")), Double.parseDouble(d.path("x").asString("0"))));
    }

    @Override
    public Optional<Route> route(String mode, double fromLat, double fromLng, double toLat, double toLng) {
        if ("car".equals(mode)) {
            JsonNode r = get(NAVI + "/v1/directions?summary=true&origin=" + num(fromLng) + "," + num(fromLat)
                    + "&destination=" + num(toLng) + "," + num(toLat), false).path("routes").path(0);
            if (r.path("result_code").asInt(-1) != 0) {
                return Optional.empty();
            }
            JsonNode sum = r.path("summary");
            return Optional.of(new Route(minutes(sum.path("duration").asInt()), sum.path("distance").asInt()));
        }
        String path = "walk".equals(mode) ? "/v2/routing/walk" : "/v2/routing/publictraffic";
        JsonNode body = get(LOCAL + path + "?start_x=" + num(fromLng) + "&start_y=" + num(fromLat)
                + "&end_x=" + num(toLng) + "&end_y=" + num(toLat), true);
        if (!"OK".equalsIgnoreCase(body.path("status").asString("OK"))) {
            return Optional.empty();
        }
        JsonNode props = "walk".equals(mode) ? body.path("route").path("properties")
                : body.path("routes").path(0).path("properties");
        if (props.isMissingNode() || !props.has("totalTime")) {
            return Optional.empty();
        }
        return Optional.of(new Route(minutes(props.path("totalTime").asInt()), props.path("totalDistance").asInt()));
    }

    private static int minutes(int seconds) {
        return Math.max(1, (int) Math.round(seconds / 60.0));
    }

    /** map=true 면 카카오맵(로컬·경로) API: 사용 설정이 꺼져 있으면 disabled. */
    private JsonNode get(String url, boolean map) {
        String key = key();
        if (key.isEmpty()) {
            throw new MapException("no_key", "카카오 키가 없습니다");
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "KakaoAK " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            status = "error";
            throw new MapException("error", "카카오 호출 실패: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MapException("error", "카카오 호출 중단");
        }
        int code = resp.statusCode();
        if (code == 401 || code == 403) {
            String body = resp.body() == null ? "" : resp.body();
            boolean disabled = map && body.contains("disabled");
            status = disabled ? "disabled" : map ? "error" : status;
            log.warn("kakao {} {}: {}", code, map ? "map" : "navi", disabled ? "카카오맵 사용 설정 꺼짐" : "권한 없음");
            throw new MapException(disabled ? "disabled" : "forbidden",
                    disabled ? "카카오맵 사용 설정을 켜 주세요" : "이 카카오 API 권한이 없습니다");
        }
        if (code / 100 != 2) {
            status = "error";
            throw new MapException("error", "카카오 응답 " + code);
        }
        if (map || "unknown".equals(status)) {
            status = "ok";
        }
        return Json.parse(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}
