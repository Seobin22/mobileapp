-- 실행 비서 v4: 단계 올리기(L1·L3·L4)·앱 잠금·딴짓 감지 (빌드문서 v4). DB 변경은 이 파일 하나.
-- 되돌리기: 새 칸은 모두 NULL 허용 또는 기본값이 있어서 v3.6 jar 도 이 스키마에서 그대로 돈다.

-- §2 개입이 시작된 이유(distraction 등). NULL = 블록 시각
ALTER TABLE interventions ADD COLUMN reason VARCHAR(20);

-- §4 딴짓 앱 목록(사용자 전용). kind: distract | ambiguous | neutral, set_by: user | default
CREATE TABLE app_rules(
    package VARCHAR(200) PRIMARY KEY,
    label VARCHAR(100),
    kind VARCHAR(10) NOT NULL,
    set_by VARCHAR(10) DEFAULT 'user' NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- §4 블록별 감시: on | off | NULL(분류 기본값), allowed_apps: JSON 배열
ALTER TABLE blocks ADD COLUMN watch VARCHAR(3);
ALTER TABLE blocks ADD COLUMN allowed_apps VARCHAR(1000);
ALTER TABLE fixed_series ADD COLUMN watch VARCHAR(3);
ALTER TABLE fixed_series ADD COLUMN allowed_apps VARCHAR(1000);

-- §6 딴짓 기록. title·domain 은 7일 뒤 NULL 로 지운다. kind: app | web | video, judged: distract | related | unsure
CREATE TABLE distraction_logs(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_id BIGINT NOT NULL,
    logical_day DATE NOT NULL,
    package VARCHAR(200) NOT NULL,
    app_label VARCHAR(100),
    kind VARCHAR(10) DEFAULT 'app' NOT NULL,
    domain VARCHAR(200),
    title VARCHAR(300),
    judged VARCHAR(10) DEFAULT 'distract' NOT NULL,
    record_only BOOLEAN DEFAULT FALSE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    minutes INT DEFAULT 0 NOT NULL,
    client_key VARCHAR(100) UNIQUE
);
CREATE INDEX distraction_logs_block ON distraction_logs(block_id);

-- §5.4 애매한 제목 판단 캐시(7일). cache_key = 대상(task·goal·category) + 도메인 + 제목 해시
CREATE TABLE distraction_judgments(
    cache_key VARCHAR(200) PRIMARY KEY,
    verdict VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- §6 block_facts 코드 칸
ALTER TABLE block_facts ADD COLUMN distraction_minutes INT;
ALTER TABLE block_facts ADD COLUMN distraction_count INT;
ALTER TABLE block_facts ADD COLUMN distraction_top_app VARCHAR(100);
