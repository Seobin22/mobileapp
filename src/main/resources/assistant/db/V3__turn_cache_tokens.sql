-- turn 별 프롬프트 캐시 토큰(읽기·쓰기). 비용 계산용. (스키마 버전 3 이며 빌드문서 v3 과는 무관)
ALTER TABLE turns ADD COLUMN cache_read_tokens INT DEFAULT 0 NOT NULL;
ALTER TABLE turns ADD COLUMN cache_write_tokens INT DEFAULT 0 NOT NULL;
