-- 빌드문서 v3.1(개정 3): 출발·준비 시간은 비서의 판단, 기본 식사 시간 폐지.

-- lead_minutes·buffer_after_min 을 누가 정했나: user | assistant | NULL(아직 안 정함 → lead 0 으로 계산)
ALTER TABLE blocks ADD COLUMN prep_set_by VARCHAR(20);
-- 이미 준비 시간이 있는 고정 블록(events 에서 옮긴 것 등)은 비서가 정한 것으로 본다(나중에 다시 정할 수 있게).
UPDATE blocks SET prep_set_by = 'assistant' WHERE kind = 'fixed' AND (lead_minutes > 0 OR buffer_after_min > 0);

-- §2.3-6: 기본 식사 시간 설정 삭제. §3.2.1: 준비 시간 기본값 설정 삭제.
DELETE FROM settings WHERE key IN ('life.meals', 'fixed.default_lead_minutes');
