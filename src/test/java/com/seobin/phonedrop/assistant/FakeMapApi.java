package com.seobin.phonedrop.assistant;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.seobin.phonedrop.assistant.agent.MapApi;

/** 테스트용 가짜 카카오(빌드문서 v3.3 §5 M1~M7). 장소·경로를 미리 넣고, 호출 수를 센다. */
public class FakeMapApi implements MapApi {

    public final Map<String, Place> places = new HashMap<>();
    /** "fromLat,fromLng>toLat,toLng" 대신 도착 장소 이름 기준으로 단순하게: 도착 lat 키 → 분 */
    public final Map<String, Integer> routeMinutes = new HashMap<>();
    public boolean key = true;
    /** null 이면 정상, 아니면 그 code 로 MapException */
    public String failCode;
    public int routeCalls;
    public int searchCalls;

    public void reset() {
        places.clear();
        routeMinutes.clear();
        key = true;
        failCode = null;
        routeCalls = 0;
        searchCalls = 0;
    }

    @Override
    public boolean hasKey() {
        return key;
    }

    @Override
    public Optional<Place> searchKeyword(String query, Double nearLat, Double nearLng) {
        searchCalls++;
        fail();
        return Optional.ofNullable(places.get(query));
    }

    @Override
    public Optional<Place> searchAddress(String query) {
        searchCalls++;
        fail();
        return Optional.ofNullable(places.get(query));
    }

    @Override
    public Optional<Route> route(String mode, double fromLat, double fromLng, double toLat, double toLng) {
        routeCalls++;
        fail();
        Integer m = routeMinutes.get(String.format(java.util.Locale.ROOT, "%.4f>%.4f", fromLat, toLat));
        return m == null ? Optional.empty() : Optional.of(new Route(m, m * 300));
    }

    private void fail() {
        if (failCode != null) {
            throw new MapException(failCode, "가짜 실패");
        }
    }

    @Override
    public String status() {
        return !key ? "no_key" : failCode == null ? "ok" : failCode;
    }
}
