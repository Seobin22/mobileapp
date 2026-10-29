-- 실행 비서 빌드문서 v3 §1: 협상·동행·먼저 말 걸기·대신 처리. (스키마 버전 4)

-- status 에 negotiating, snoozed 추가(문자열이라 스키마 변경 없음)
ALTER TABLE interventions ADD COLUMN negotiate_until TIMESTAMP WITH TIME ZONE;
-- move_block 으로 snoozed 가 될 때 옮겨진 분(새 start − 기존 start). get_facts 의 snoozed_today_minutes 용
ALTER TABLE interventions ADD COLUMN snoozed_minutes INT;

CREATE TABLE work_products(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks(id),
    title VARCHAR(500) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    content_md VARCHAR(100000) NOT NULL,
    sources VARCHAR(20000) DEFAULT '[]' NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE blocks ADD COLUMN work_product_id BIGINT REFERENCES work_products(id);

CREATE TABLE wakeups(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fire_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    task_id BIGINT REFERENCES tasks(id),
    status VARCHAR(20) DEFAULT 'scheduled' NOT NULL,
    created_by_turn BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX wakeups_status_fire ON wakeups(status, fire_at);

CREATE TABLE daily_flags(
    logical_day DATE PRIMARY KEY,
    morning_done BOOLEAN DEFAULT FALSE NOT NULL,
    evening_done BOOLEAN DEFAULT FALSE NOT NULL
);
