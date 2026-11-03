package com.seobin.phonedrop.assistant.agent;

import java.util.Optional;

/**
 * 지도(빌드문서 v3.3 §3). 운영은 KakaoMapApi, 테스트는 가짜 응답. 실패는 MapException(code 로 원인 구분).
 * 좌표는 WGS84(lat 위도, lng 경도).
 */
public interface MapApi {

    record Place(String name, String address, double lat, double lng) {
    }

    record Route(int minutes, int distanceM) {
    }

    /** code: no_key | disabled(카카오맵 사용 설정 꺼짐) | forbidden(이 API 권한 없음) | error */
    class MapException extends RuntimeException {
        private final String code;

        public MapException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    boolean hasKey();

    /** 키워드 장소 검색 첫 결과. nearLat·nearLng 가 있으면 그 근처를 우선한다. */
    Optional<Place> searchKeyword(String query, Double nearLat, Double nearLng);

    /** 키워드 검색 후보 여러 개(장소 탭의 지도 검색). 기본은 첫 결과 하나. */
    default java.util.List<Place> searchKeywordList(String query, Double nearLat, Double nearLng, int size) {
        return searchKeyword(query, nearLat, nearLng).map(java.util.List::of).orElse(java.util.List.of());
    }

    /** 좌표 → 주소(지도에서 찍은 위치의 이름). */
    default Optional<Place> reverse(double lat, double lng) {
        return Optional.empty();
    }

    /** 주소 검색 첫 결과. */
    Optional<Place> searchAddress(String query);

    /** mode: transit | car | walk. 경로가 없으면 empty. */
    Optional<Route> route(String mode, double fromLat, double fromLng, double toLat, double toLng);

    /** 관리 화면 연결 상태: ok | no_key | disabled | limit | error | unknown(아직 호출 안 함). */
    default String status() {
        return hasKey() ? "unknown" : "no_key";
    }
}
