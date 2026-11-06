-- 실행 비서 v3.5: 하루 기록·패턴 분석·사용자 맞춤 메모리 (빌드문서 v3.5)
-- 새 테이블은 외래 키를 두지 않는다(블록이 지워져도 기록은 남고, 테스트 초기화 순서에 묶이지 않게).

-- §1 분류. category 는 study|work|class|exercise|chores|selfcare|rest|social|travel|other
-- category_src: user | assistant | inferred | system (NULL = 아직 안 정함, 물려받기 대상)
ALTER TABLE goals ADD COLUMN category VARCHAR(20) DEFAULT 'other' NOT NULL;
ALTER TABLE goals ADD COLUMN category_src VARCHAR(10);
ALTER TABLE goals ADD COLUMN category_hint VARCHAR(50);
ALTER TABLE tasks ADD COLUMN category VARCHAR(20) DEFAULT 'other' NOT NULL;
ALTER TABLE tasks ADD COLUMN category_src VARCHAR(10);
ALTER TABLE tasks ADD COLUMN category_hint VARCHAR(50);
ALTER TABLE blocks ADD COLUMN category VARCHAR(20) DEFAULT 'other' NOT NULL;
ALTER TABLE blocks ADD COLUMN category_src VARCHAR(10);
ALTER TABLE blocks ADD COLUMN category_hint VARCHAR(50);
-- 반복 일정: 회차가 물려받는다
ALTER TABLE fixed_series ADD COLUMN category VARCHAR(20) DEFAULT 'other' NOT NULL;
ALTER TABLE fixed_series ADD COLUMN category_src VARCHAR(10);
ALTER TABLE fixed_series ADD COLUMN category_hint VARCHAR(50);
UPDATE blocks SET category = 'travel', category_src = 'system' WHERE link_role = 'travel';

-- §2.1 블록 하나당 한 줄
CREATE TABLE block_facts(
    block_id BIGINT PRIMARY KEY,
    logical_day DATE NOT NULL,
    kind VARCHAR(10) NOT NULL,
    category VARCHAR(20) DEFAULT 'other' NOT NULL,
    goal_id BIGINT,
    task_id BIGINT,
    planned_start_first TIMESTAMP WITH TIME ZONE,
    planned_start_last TIMESTAMP WITH TIME ZONE,
    planned_minutes INT,
    actual_start TIMESTAMP WITH TIME ZONE,
    actual_end TIMESTAMP WITH TIME ZONE,
    actual_minutes INT,
    start_delay_min INT,
    drift_min INT,
    move_count INT DEFAULT 0 NOT NULL,
    snooze_count INT DEFAULT 0 NOT NULL,
    band VARCHAR(10),
    weekday INT,
    tactics VARCHAR(4000) DEFAULT '[]' NOT NULL,
    status_final VARCHAR(10) NOT NULL,
    outcome VARCHAR(10),
    outcome_src VARCHAR(10),
    progress_done INT,
    progress_src VARCHAR(10),
    focus INT,
    focus_src VARCHAR(10),
    reason_tags VARCHAR(4000),
    reason_src VARCHAR(10),
    outcome_note VARCHAR(1000),
    focus_note VARCHAR(1000),
    reason_note VARCHAR(1000),
    extra_note VARCHAR(1000),
    card_state VARCHAR(10) DEFAULT 'none' NOT NULL,
    card_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX block_facts_day ON block_facts(logical_day);
CREATE INDEX block_facts_card ON block_facts(card_state);

-- §2.2 하루 한 줄
CREATE TABLE day_facts(
    logical_day DATE PRIMARY KEY,
    sleep INT,
    sleep_src VARCHAR(10),
    sleep_note VARCHAR(1000),
    day_condition INT, -- 문서의 condition(H2 예약어와 겹치지 않게 이름만 바꿈)
    condition_src VARCHAR(10),
    condition_note VARCHAR(1000),
    day_note VARCHAR(1000),
    planned_count INT DEFAULT 0 NOT NULL,
    done_count INT DEFAULT 0 NOT NULL,
    partial_count INT DEFAULT 0 NOT NULL,
    missed_count INT DEFAULT 0 NOT NULL,
    focus_avg DOUBLE PRECISION,
    total_focus_minutes INT DEFAULT 0 NOT NULL,
    card_sent BOOLEAN DEFAULT FALSE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- §3·§4 카드(정리 카드 kind=outcome, 아침 카드 kind=day). block_ids·payload 는 JSON 문자열.
CREATE TABLE cards(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(10) NOT NULL,
    logical_day DATE NOT NULL,
    evening BOOLEAN DEFAULT FALSE NOT NULL,
    status VARCHAR(10) DEFAULT 'sent' NOT NULL,
    block_ids VARCHAR(2000) DEFAULT '[]' NOT NULL,
    payload VARCHAR(16000) DEFAULT '{}' NOT NULL,
    message_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX cards_day ON cards(logical_day, kind);
ALTER TABLE messages ADD COLUMN card_id BIGINT;
-- §7.2 "이건 기억하지 마" 한 메시지는 사실 뽑기에서 뺀다
ALTER TABLE messages ADD COLUMN no_memory BOOLEAN DEFAULT FALSE NOT NULL;

-- §5 숫자 통계(매일 밤 다시 만든다)
CREATE TABLE pattern_stats(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric VARCHAR(40) NOT NULL,
    dimension VARCHAR(20) NOT NULL,
    stat_key VARCHAR(100) NOT NULL,
    stat_value DOUBLE PRECISION,
    raw_value DOUBLE PRECISION,
    n INT NOT NULL,
    baseline DOUBLE PRECISION,
    notable BOOLEAN DEFAULT FALSE NOT NULL,
    score DOUBLE PRECISION,
    period_days INT NOT NULL,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX pattern_stats_metric ON pattern_stats(metric, dimension);

-- §7 기억 층
CREATE TABLE memory_profile(
    section VARCHAR(20) PRIMARY KEY,
    content VARCHAR(2000) DEFAULT '' NOT NULL,
    updated_by VARCHAR(20),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
INSERT INTO memory_profile(section) VALUES ('life'), ('personality'), ('likes'), ('now'), ('work_style');

CREATE TABLE memory_items(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(20) NOT NULL,
    category VARCHAR(20) DEFAULT 'other' NOT NULL,
    content VARCHAR(4000) NOT NULL,
    evidence VARCHAR(4000),
    confidence DOUBLE PRECISION DEFAULT 0.5 NOT NULL,
    goal_id BIGINT,
    status VARCHAR(10) DEFAULT 'active' NOT NULL,
    created_by VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX memory_items_kind ON memory_items(status, kind, category);

CREATE TABLE memory_ops_log(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT,
    op VARCHAR(10) NOT NULL,
    before_text VARCHAR(4000),
    after_text VARCHAR(4000),
    actor VARCHAR(20) NOT NULL,
    at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 밤 정리의 '확인 필요' 목록(다음 아침 브리핑에서 한 번만 묻는다)
CREATE TABLE memory_checks(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    text VARCHAR(500) NOT NULL,
    status VARCHAR(10) DEFAULT 'open' NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    asked_at TIMESTAMP WITH TIME ZONE
);

-- 한 번만 하는 작업·커서(기존 memory 이전, 기존 블록 채우기, 사실 뽑기 위치 등)
CREATE TABLE memory_state(
    state_key VARCHAR(50) PRIMARY KEY,
    state_value VARCHAR(200) NOT NULL
);
