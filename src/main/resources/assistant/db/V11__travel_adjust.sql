-- 2026-11-06 이동 블록 보강: 출발 미루기·지각 예상. 모두 NULL 허용(복귀 태그 1106_1_v3.4.1전).
-- 사용자가 미룬 출발 시각(약속 시각이 바뀌면 지운다)
ALTER TABLE blocks ADD COLUMN depart_at TIMESTAMP WITH TIME ZONE;
-- 늦게 출발해서 약속에 늦을 것으로 보이는 분
ALTER TABLE blocks ADD COLUMN travel_late_min INT;
