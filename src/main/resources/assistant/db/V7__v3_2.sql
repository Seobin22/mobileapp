-- 빌드문서 v3.2: 반복 고정 일정·메모·블록 기록·연결 블록·turn 진행 상태.
-- 새 컬럼은 모두 NULL 허용이거나 기본값이 있어 v3.1 코드(태그 1102_4_v3.2전)로 되돌려도 그대로 동작한다.

-- §2 반복 고정 일정. interval 은 H2 예약어라 interval_n, by_days 는 "1,3"(월=1..일=7) 문자열.
CREATE TABLE fixed_series(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    freq VARCHAR(10) NOT NULL,
    interval_n INT DEFAULT 1 NOT NULL,
    by_days VARCHAR(20),
    month_day INT,
    start_time TIME NOT NULL,
    duration_min INT NOT NULL,
    start_date DATE NOT NULL,
    until_date DATE,
    lead_minutes INT DEFAULT 0 NOT NULL,
    buffer_after_min INT DEFAULT 0 NOT NULL,
    prep_set_by VARCHAR(20),
    location VARCHAR(500),
    goal_id BIGINT REFERENCES goals(id),
    first_action VARCHAR(500),
    memo VARCHAR(2000) DEFAULT '' NOT NULL,
    memo_updated_at TIMESTAMP WITH TIME ZONE,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- action: skip(이 날 없음) | moved(이 날만 다른 시각) | conflict(다른 고정 블록과 겹쳐 만들지 않음, 알림 1회)
CREATE TABLE series_exceptions(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    series_id BIGINT NOT NULL REFERENCES fixed_series(id),
    occurrence_date DATE NOT NULL,
    action VARCHAR(20) NOT NULL,
    moved_block_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX series_exceptions_series ON series_exceptions(series_id, occurrence_date);

ALTER TABLE blocks ADD COLUMN series_id BIGINT REFERENCES fixed_series(id);
ALTER TABLE blocks ADD COLUMN occurrence_date DATE;
-- §3 고정 블록 메모: 단발이면 그 일정의 메모, 반복이면 '이번만' 메모(사용자만 편집)
ALTER TABLE blocks ADD COLUMN memo VARCHAR(2000) DEFAULT '' NOT NULL;
ALTER TABLE blocks ADD COLUMN memo_updated_at TIMESTAMP WITH TIME ZONE;
-- §6 계획 블록이 어떤 고정 일정을 위한 준비인지
ALTER TABLE blocks ADD COLUMN linked_fixed_block_id BIGINT REFERENCES blocks(id);
ALTER TABLE blocks ADD CONSTRAINT blocks_series_occurrence UNIQUE(series_id, occurrence_date);

-- §4 블록 기록
CREATE TABLE block_logs(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    block_id BIGINT NOT NULL REFERENCES blocks(id),
    at TIMESTAMP WITH TIME ZONE NOT NULL,
    type VARCHAR(30) NOT NULL,
    actor VARCHAR(20) NOT NULL,
    content VARCHAR(500) NOT NULL,
    data VARCHAR(4000)
);
CREATE INDEX block_logs_block_at ON block_logs(block_id, at);

-- P3.2 turn 진행 상태, P0.2 turn 기록(도구 이름·소요 시간)
ALTER TABLE turns ADD COLUMN stage VARCHAR(20);
ALTER TABLE turns ADD COLUMN stage_text VARCHAR(200);
ALTER TABLE turns ADD COLUMN queued_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE turns ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE turns ADD COLUMN tool_names VARCHAR(2000);
ALTER TABLE turns ADD COLUMN duration_ms INT;
