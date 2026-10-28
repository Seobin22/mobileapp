# 실행 비서 — 결정 기록

빌드문서에 없는 세부를 정한 것, 문서와 다르게 구현한 것을 한 줄씩 남긴다.

## v1 (2026-10-29)

### 구조
- **서버 스택**: 문서의 Python/FastAPI/PostgreSQL/Docker 대신 기존 Spring Boot 앱(`com.seobin.phonedrop.assistant`) 안에 구현. 레포 규칙(새 포트·서비스 금지, 512MB JVM)과 사용자 선택(2026-10-29). API 계약·상태 머신·테스트(T1~T15)는 문서와 같다.
- **API 주소**: `https://test.seobin.page/api/assistant` 아래에 문서 경로를 그대로 붙인다(`/v1/sync`, `/v1/actions` …). 인증 없는 `/health` 는 `/api/assistant/health`. 경로는 모든 호스트에서 열리지만 앱 기본값은 test.seobin.page.
- **DB**: H2 파일 DB `data/assistant/assistant.mv.db`(서버 `/root/data/assistant/`). 의존성 `spring-boot-starter-jdbc` + `h2` 추가. jsonb 대신 VARCHAR 에 JSON 문자열.
- **마이그레이션**: Alembic 대신 `AssistantDb` 가 `assistant/db/V{n}__*.sql` 을 순서대로 적용하고 `schema_version` 에 기록. 설정 기본값은 시작할 때 없는 키만 넣는다.
- **컬럼 이름**: `blocks.start/end` 는 H2 예약어 충돌로 `start_at/end_at`. API 에서는 start/end.
- **worker 1개 보장**: advisory lock 대신 앱 안의 `@Scheduled` 스레드 하나(`JobWorker`, 1초 폴링). H2 파일 DB 는 한 프로세스만 열 수 있어 두 번째 인스턴스는 시작하지 못한다.
- **동시성**: `FOR UPDATE SKIP LOCKED` 대신 모든 상태 변경(작업 실행·액션·관리 API·설정)을 하나의 락 + 트랜잭션(`Tx`)으로 직렬화. 단일 사용자라 충분하다.
- **푸시 시점**: 트랜잭션 안에서 만든 푸시는 커밋 뒤에 보낸다(롤백된 변경을 알리지 않도록).
- **FCM**: firebase-admin(의존성이 크다) 대신 HTTP v1 API 를 JDK 만으로 호출(`FirebasePush`, 서비스 계정 JWT 서명). Firebase 미준비라 운영은 `ASSISTANT_PUSH_MODE=fake`(기본) — 발송 내용은 관리 화면 "최근 푸시"에만 남는다(메모리, 재시작 시 사라짐).
- **API 토큰**: 환경변수 `ASSISTANT_API_TOKEN` 이 없으면 첫 시작 때 `data/assistant/api_token`(권한 600)을 만들어 쓴다. 저장소·로그에 값을 남기지 않는다.
- **배포**: docker compose 대신 기존 `deploy.yml`(jar 교체 + systemctl restart). 마이그레이션은 앱 시작 시 자동.

### 동작 세부
- `start_intervention` 은 start ≥ now 이면 예약한다(문서는 start > now). 지금 시각으로 등록한 블록도 개입하도록.
- plan_version 은 블록 생성·시각 변경·취소·텍스트 변경, 액션으로 블록이 started 가 될 때, 설정 변경 때 올린다. 개입 시작(planned→nudging)처럼 상태만 바뀔 때는 올리지 않는다.
- plan.sync data 에 `local_backup_grace_seconds`, `reshow_minutes` 를 추가했다(앱 로컬 백업·재표시용).
- intervention.level 에 `block_end` 를, intervention.end 에 `block_id` 를 추가했다(앱이 로컬 개입과 맞추기 위해).
- `intervention_logs.intervention_id` 는 null 허용 + `block_id` 컬럼 추가: local_fired 는 서버 개입이 생기기 전에 올 수 있다.
- 설정이 바뀌면 진행 중 개입의 `suspend_check` 를 다시 계산한다(T4: 개입 도중 방해 금지 추가).
- `suspend_check` 는 실행 시점에 실제로 조용한 시간인지 다시 확인한다.
- `expire`·`suspend_check`·`start`(acked)·삭제로 개입이 끝나면 남은 reshow/expire/suspend_check 작업을 취소한다.
- PATCH 로 nudging 블록의 시각을 바꾸면 진행 중 개입을 cancelled 로 끝내고 블록을 planned 로 되돌린다. started·끝난 블록의 시각 변경은 422.
- PATCH 는 할 일의 현재 블록(취소되지 않은 마지막 블록)에 적용한다(v1 은 할 일 하나에 블록 하나).
- 등록 검증 순서: 과거 → 5분 미만 → 블록 겹침 → 조용한 시간 겹침(길이 0 일 때 엉뚱한 겹침 사유가 나오지 않도록 길이를 먼저).
- `start` 액션: 개입이 없거나 끝났을 때 블록이 아직 안 끝났으면 시각은 그대로 두고 started 로만 바꾼다. 끝났으면 now~now+est_minutes 로 옮기고 겹치는 planned 블록을 취소한다.
- `start` 액션의 intervention_id 가 `"local:9"` 같은 로컬 id 이면 block_id 로 진행 중 개입을 찾는다.
- 같은 idempotency_key 의 두 번째 요청은 저장된 결과에 `status:"duplicate"` 로 바꿔 돌려준다.
- 하트비트에서 모르는 device_id 는 404(앱이 다시 register). 무효 토큰 기기는 발송 때 삭제.
- `jobs.late_threshold_seconds` 는 늦게 실행된 작업을 경고 로그로 남기는 데만 쓴다.
- 관리 API 추가: `GET /v1/admin/status`(관리 화면용 — 진행 중 개입, 기기, 예약 작업, 최근 푸시).

### 웹
- `test.seobin.page` 의 현재 시간 페이지를 "실행 비서 관리" 화면으로 바꿨다(`static/test.html`). 할 일 등록·수정·삭제, 생활 설정, 상태, 최근 푸시, 휴대폰 대신 [시작] 보내기. 토큰은 브라우저 localStorage.

### 안드로이드
- APK 는 GitHub Actions(`android.yml`)로 빌드해 아티팩트로 올린다(이 PC 에 Android SDK 없음). Gradle wrapper 는 커밋하지 않고 CI 에서 Gradle 8.11.1 을 설치해 쓴다.
- `google-services.json` 이 있을 때만 google-services 플러그인을 켠다. 없으면 FCM 없이 동작: 기기 토큰은 `nofcm-<uuid>`, 계획은 앱 시작·15분 주기 GET /v1/sync 로 받고, 화면은 로컬 백업 알람으로 뜬다.
- 하트비트 작업이 매번 GET /v1/sync 도 한다(FCM 유실 대비).
- 로컬 개입이 서버 id 로 바뀌면 Room 에서 `local:{block_id}` 행을 지우고 서버 id 행으로 대체한다.
- 개입 화면은 [시작]이 아닌 방법(뒤로·홈·화면 꺼짐)으로 나가면 dismiss + 로컬 재표시를 예약하고 화면을 닫는다.
- 정확한 알람 권한이 없으면 부정확한 알람으로 대신 예약한다(권한 화면이 빨간색으로 알림).
- 하트비트 events(first_unlock)는 v1 에서 보내지 않는다(서버도 저장만 하고 v3 에서 쓴다).
- 방해 금지 편집은 한 줄에 하나 `1,2,3,4,5 13:00-14:00` 형식.
