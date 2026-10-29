-- 빌드문서 v3.1: 블록 통일(고정·계획)·목표 관리·풀스크린 대화.
-- 데이터 이전(events → 고정 블록, 목표 이름표·색)은 코드(V5DataMigration)가 한다(색 배정·개입 예약이 필요해서).

ALTER TABLE blocks ADD COLUMN kind VARCHAR(10) DEFAULT 'planned' NOT NULL;
ALTER TABLE blocks ADD COLUMN title VARCHAR(500) DEFAULT '' NOT NULL;
ALTER TABLE blocks ADD COLUMN goal_id BIGINT REFERENCES goals(id);
ALTER TABLE blocks ALTER COLUMN task_id SET NULL;
ALTER TABLE blocks ADD COLUMN lead_minutes INT DEFAULT 0 NOT NULL;
ALTER TABLE blocks ADD COLUMN buffer_after_min INT DEFAULT 0 NOT NULL;
ALTER TABLE blocks ADD COLUMN location VARCHAR(500);
ALTER TABLE blocks ADD COLUMN source VARCHAR(20) DEFAULT 'chat' NOT NULL;
ALTER TABLE blocks ADD COLUMN external_id VARCHAR(500);
ALTER TABLE blocks ADD COLUMN sensitive BOOLEAN DEFAULT FALSE NOT NULL;
-- events 에서 옮겨 온 고정 블록 표식(다시 실행해도 중복 복사하지 않도록)
ALTER TABLE blocks ADD COLUMN migrated_event_id BIGINT;

ALTER TABLE goals ADD COLUMN label VARCHAR(20) DEFAULT '' NOT NULL;
ALTER TABLE goals ADD COLUMN color VARCHAR(20) DEFAULT '' NOT NULL;
ALTER TABLE goals ADD COLUMN strategy_summary VARCHAR(1000) DEFAULT '' NOT NULL;
-- {"unit":"챕터","total":12,"done":5}
ALTER TABLE goals ADD COLUMN progress VARCHAR(2000);
ALTER TABLE goals ADD COLUMN waiting_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE goals ADD COLUMN waiting_reason VARCHAR(2000);
ALTER TABLE goals ADD COLUMN priority INT DEFAULT 3 NOT NULL;

ALTER TABLE messages ADD COLUMN intervention_id BIGINT REFERENCES interventions(id);

ALTER TABLE interventions ALTER COLUMN negotiate_until RENAME TO talk_until;
UPDATE interventions SET status = 'talking' WHERE status = 'negotiating';

-- 코드가 한 번 하는 데이터 이전의 결과 기록
CREATE TABLE data_migrations(
    name VARCHAR(100) PRIMARY KEY,
    result VARCHAR(4000) NOT NULL,
    ran_at TIMESTAMP WITH TIME ZONE NOT NULL
);
