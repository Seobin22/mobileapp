-- 목표 마감 지남·시간 부족·할 일 모두 끝남 질문(2026-11-02 사용자 지시). 코드가 한 번만 묻는다.
-- 모두 NULL 허용이라 이전 코드(태그 1102_5_목표마감수정전)로 되돌려도 그대로 동작한다.

-- 마감 지남 질문을 보낸 시각(마감이 바뀌면 지운다), 다음 날 아침에 한 번 상기한 시각
ALTER TABLE goals ADD COLUMN deadline_prompted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE goals ADD COLUMN deadline_reminded_at TIMESTAMP WITH TIME ZONE;
-- 마감까지 넣을 빈 시간이 없다는 질문(마감이 바뀌면 지운다)
ALTER TABLE goals ADD COLUMN shortage_prompted_at TIMESTAMP WITH TIME ZONE;
-- 할 일이 모두 끝났다는 질문(새 할 일이 생기면 지운다)
ALTER TABLE goals ADD COLUMN done_prompted_at TIMESTAMP WITH TIME ZONE;

-- 목표 질문 메시지가 어느 목표인지(앱이 버튼으로 목표 API 를 부른다)
ALTER TABLE messages ADD COLUMN goal_id BIGINT REFERENCES goals(id);
