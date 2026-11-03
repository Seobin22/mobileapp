-- 빌드문서 v3.3: 방해 허용 정도·지도 읽기(장소·이동 시간)·문서 받기.
-- 새 컬럼은 모두 NULL 허용이라 이전 코드(태그 1103_1_v3.3전)로 되돌려도 그대로 동작한다.

-- §1 방해 허용 정도: dnd(방해 금지) | quiet(조용히) | allow(알림 허용). NULL 이면 quiet.
ALTER TABLE blocks ADD COLUMN interrupt_level VARCHAR(10);
ALTER TABLE blocks ADD COLUMN interrupt_set_by VARCHAR(20);
ALTER TABLE fixed_series ADD COLUMN interrupt_level VARCHAR(10);
ALTER TABLE fixed_series ADD COLUMN interrupt_set_by VARCHAR(20);
-- dnd 일정 중 보내지 않고 모아 둔 비서 메시지(일정이 끝나면 묶음 메시지 1개로 보낸다)
ALTER TABLE messages ADD COLUMN held_block_id BIGINT;
-- §4 채팅으로 올린 자료(말풍선 파일 카드)
ALTER TABLE messages ADD COLUMN document_id BIGINT;

-- §3.2 장소 → 좌표. place_source: kakao | estimate | user
ALTER TABLE blocks ADD COLUMN place_name VARCHAR(300);
ALTER TABLE blocks ADD COLUMN lat DOUBLE PRECISION;
ALTER TABLE blocks ADD COLUMN lng DOUBLE PRECISION;
ALTER TABLE blocks ADD COLUMN place_source VARCHAR(20);
ALTER TABLE fixed_series ADD COLUMN place_name VARCHAR(300);
ALTER TABLE fixed_series ADD COLUMN lat DOUBLE PRECISION;
ALTER TABLE fixed_series ADD COLUMN lng DOUBLE PRECISION;
ALTER TABLE fixed_series ADD COLUMN place_source VARCHAR(20);

-- §3.3 이동 시간. direction: arrive(여기로 오는 길) | depart(여기서 다음 장소로), source: kakao | estimate
CREATE TABLE block_travel(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_id BIGINT NOT NULL REFERENCES blocks(id),
    direction VARCHAR(10) NOT NULL,
    from_label VARCHAR(300) NOT NULL,
    to_label VARCHAR(300) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    minutes INT NOT NULL,
    distance_m INT NOT NULL,
    source VARCHAR(20) NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX block_travel_block ON block_travel(block_id, direction);

-- 같은 출발·도착·수단·시간대(1시간) 결과는 7일 동안 다시 부르지 않는다. 하루 호출 수도 여기서 센다.
CREATE TABLE travel_cache(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_key VARCHAR(40) NOT NULL,
    to_key VARCHAR(40) NOT NULL,
    mode VARCHAR(10) NOT NULL,
    hour_bucket INT NOT NULL,
    minutes INT NOT NULL,
    distance_m INT NOT NULL,
    source VARCHAR(20) NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX travel_cache_key ON travel_cache(from_key, to_key, mode, hour_bucket);

CREATE TABLE kakao_calls(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api VARCHAR(20) NOT NULL,
    ok BOOLEAN NOT NULL,
    called_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX kakao_calls_at ON kakao_calls(called_at);

-- §4 문서 받기. 파일은 data/assistant/documents/ 에 두고 DB 백업과 함께 백업한다.
-- status: processing | ready | failed. extracted 는 JSON 문자열.
CREATE TABLE documents(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goal_id BIGINT REFERENCES goals(id),
    task_id BIGINT,
    block_id BIGINT,
    filename VARCHAR(300) NOT NULL,
    mime VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error VARCHAR(1000),
    text_chars INT DEFAULT 0 NOT NULL,
    pages INT,
    summary_md VARCHAR(4000) DEFAULT '' NOT NULL,
    extracted VARCHAR(20000),
    message VARCHAR(2000),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE document_chunks(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id),
    idx INT NOT NULL,
    page INT,
    text VARCHAR(8000) NOT NULL
);
CREATE INDEX document_chunks_doc ON document_chunks(document_id, idx);
