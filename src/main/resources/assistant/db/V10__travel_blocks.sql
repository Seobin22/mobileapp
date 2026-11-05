-- 빌드문서 v3.4: 이동 블록(연결 블록, link_role=travel). 모두 NULL 허용이라 이전 코드(태그 1105_1_v3.4전)로 되돌려도 동작한다.
-- 되돌리면 이동 블록은 일반 계획 블록(할 일 없음)으로 보인다.
ALTER TABLE blocks ADD COLUMN link_role VARCHAR(20);
-- arrive(이 고정 일정으로 가는 길) | home(그날 마지막 장소에서 집으로)
ALTER TABLE blocks ADD COLUMN travel_direction VARCHAR(10);
-- 이동 촉박일 때 부족한 분
ALTER TABLE blocks ADD COLUMN travel_tight_min INT;
-- 사용자가 직접 고친 이동 시간(있으면 최우선)
ALTER TABLE blocks ADD COLUMN travel_minutes_user INT;
CREATE INDEX blocks_link ON blocks(linked_fixed_block_id, link_role);
-- v3.3 이전에 사용자가 정한 lead(출발 전체 = 준비 + 이동). 이동 블록이 생기면 준비 알림은 lead − 이동 시간에 보낸다.
-- 사용자가 앱에서 준비 시간을 다시 정하면(새 의미: 준비만) 지운다.
ALTER TABLE blocks ADD COLUMN lead_includes_travel BOOLEAN DEFAULT FALSE NOT NULL;
