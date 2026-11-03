package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.KakaoMapApi;
import com.seobin.phonedrop.assistant.agent.MapApi;
import com.seobin.phonedrop.assistant.agent.TravelService;

import tools.jackson.databind.JsonNode;

/**
 * 지도 읽기 설정(빌드문서 v3.3 §3.1·§3.6): 앱 설정의 집 주소·주 이동수단, 관리 화면의 '지도 연결'(카카오 REST 키).
 * 키 값은 다시 돌려주지 않고 로그에도 남기지 않는다.
 */
@RestController
@RequestMapping("/api/assistant/v1")
public class TravelController {

    private final Tx tx;
    private final TravelService travel;
    private final SettingsService settings;
    private final PlanService plan;
    private final MapApi map;

    public TravelController(Tx tx, TravelService travel, SettingsService settings, PlanService plan, MapApi map) {
        this.tx = tx;
        this.travel = travel;
        this.settings = settings;
        this.plan = plan;
        this.map = map;
    }

    @GetMapping("/travel")
    public Map<String, Object> get() {
        return tx.run(travel::status);
    }

    /** {home_address?, mode?}. 주소를 저장하면 좌표로 바꾸고 찾은 주소를 돌려준다. */
    @PutMapping("/travel")
    public Map<String, Object> put(@RequestBody JsonNode body) {
        return tx.run(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            if (body.hasNonNull("mode")) {
                settings.put("travel.mode", Json.MAPPER.valueToTree(body.get("mode").asString("")));
                if (!body.has("home_address")) {
                    travel.recomputeAhead(7);
                }
            }
            if (body.has("home_address")) {
                Optional<MapApi.Place> place = travel.setHome(body.path("home_address").asString(""));
                r.put("found", place.isPresent());
                r.put("found_address", place.map(p -> p.address().isBlank() ? p.name() : p.address()).orElse(null));
            }
            plan.bumpAndSync();
            r.putAll(travel.status());
            r.put("ok", true);
            return r;
        });
    }

    /** 관리 화면 '지도 연결': 카카오 REST API 키 저장(서버 data/assistant/kakao_api_key, 권한 600). */
    @PostMapping("/admin/kakao_key")
    public Map<String, Object> saveKey(@RequestBody JsonNode body) throws IOException {
        String key = body.path("key").asString("").trim();
        if (key.isEmpty() || key.length() > 200 || !key.matches("[A-Za-z0-9_-]+")) {
            throw ApiException.badRequest("카카오 REST API 키 형식이 맞지 않습니다");
        }
        if (!(map instanceof KakaoMapApi kakao)) {
            throw ApiException.unprocessable("지도 연결을 쓸 수 없습니다");
        }
        kakao.saveKey(key);
        // 바로 한 번 불러 연결 상태를 확인한다(사용 설정이 꺼져 있으면 '사용 설정 꺼짐').
        try {
            map.searchKeyword("서울역", null, null);
        } catch (MapApi.MapException ignored) {
            // 상태는 map.status() 에 남는다.
        }
        return tx.run(travel::status);
    }

    @GetMapping("/admin/kakao_status")
    public Map<String, Object> status() {
        return tx.run(travel::status);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
