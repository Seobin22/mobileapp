-- 2026-11-20 블록별 알림 방식(비서가 정하고 코드가 실행). off | once | normal | strong, NULL = 기본(고정 일정 once, 할 일 normal)
-- 되돌리기: 칸만 추가했으므로 예전 jar 도 이 스키마에서 그대로 돈다.
ALTER TABLE blocks ADD COLUMN nudge_mode VARCHAR(10);
ALTER TABLE blocks ADD COLUMN nudge_set_by VARCHAR(10);
ALTER TABLE fixed_series ADD COLUMN nudge_mode VARCHAR(10);
