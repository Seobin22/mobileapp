-- 실행 비서 v3.6: 방해 금지·적극 개입 시간대, 음성 대화 탭 (빌드문서 v3.6). DB 변경은 이 파일 하나.
-- 새 테이블은 외래 키를 두지 않는다(v3.5 와 같은 이유).

-- §1.1 시간대(사용자 전용. 비서 도구는 읽기만 한다)
-- kind: dnd | active, days: JSON 요일 배열(월=1 … 일=7), end_type: time | first_block
CREATE TABLE time_windows(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kind VARCHAR(10) NOT NULL,
    name VARCHAR(40) NOT NULL,
    days VARCHAR(100) NOT NULL,
    start_time VARCHAR(5) NOT NULL,
    end_type VARCHAR(12) DEFAULT 'time' NOT NULL,
    end_time VARCHAR(5),
    end_fallback_time VARCHAR(5) DEFAULT '12:00' NOT NULL,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 시간대 한 번 열림(회차)의 진행 상태: 알람 반복·확인·LLM 먼저 말 걸기 횟수·끝 처리
CREATE TABLE time_window_runs(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_id BIGINT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    occ_start TIMESTAMP WITH TIME ZONE NOT NULL,
    alarm_count INT DEFAULT 0 NOT NULL,
    alarm_last_at TIMESTAMP WITH TIME ZONE,
    acked_at TIMESTAMP WITH TIME ZONE,
    llm_count INT DEFAULT 0 NOT NULL,
    llm_last_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(window_id, occ_start)
);

-- §2.2 방해 금지 시간대에 모아 둔 비서 메시지(끝나면 묶음 1개). held_block_id 와 같은 방식
ALTER TABLE messages ADD COLUMN held_window_id BIGINT;
-- §1.2 답장에 붙는 버튼(open_settings)
ALTER TABLE messages ADD COLUMN action VARCHAR(20);
-- §3 음성 대화: channel = chat | voice, 끊긴 답은 interrupted
ALTER TABLE messages ADD COLUMN channel VARCHAR(10) DEFAULT 'chat' NOT NULL;
ALTER TABLE messages ADD COLUMN interrupted BOOLEAN DEFAULT FALSE NOT NULL;

-- §3.1 음성 세션
CREATE TABLE voice_sessions(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    end_reason VARCHAR(30),
    stt_seconds DOUBLE DEFAULT 0 NOT NULL,
    tts_chars INT DEFAULT 0 NOT NULL,
    llm_tokens INT DEFAULT 0 NOT NULL
);

-- §3.6 음성 승인: 카드를 띄운 세션과 카드를 읽은 비서 메시지
ALTER TABLE confirmations ADD COLUMN voice_session_id BIGINT;
ALTER TABLE confirmations ADD COLUMN voice_read_message_id BIGINT;
ALTER TABLE confirmations ADD COLUMN voice_quote VARCHAR(500);
