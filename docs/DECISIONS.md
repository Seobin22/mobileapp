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
- (v1 점검 후 추가) 앱도 서버 expire·suspend 규칙을 스스로 지킨다: intervention.level 에 `expire_at`, plan.sync 에 `expire_grace_minutes` 를 실어 보내고,
  앱은 그 시각이 지났거나 조용한 시간이면 재표시를 멈추고 알림을 지운다(알림에도 timeoutAfter). FCM 유실·미설정 때 화면이 끝없이 뜨는 것을 막는다.
- (v1 점검 후 추가) 로컬 백업으로 뜬 개입은 앱 타이머가 reshow_minutes 마다 다시 띄운다(서버 reshow 푸시를 대신). 서버 푸시가 오면 이 타이머는 취소된다.

## v2 (2026-10-29)

### 구조
- **모델**: 사용자가 `claude-sonnet-5-5` 로 정했다. `application.properties` 의 `assistant.agent-model=${AGENT_MODEL:claude-sonnet-5-5}`.
  비어 있으면 서버 전체를 멈추는 대신(같은 앱에 다른 기능이 있다) 오류 로그를 남기고 채팅 turn 만 실패시킨다.
- **API 키**: 사용자 선택으로 관리 화면(test.seobin.page)에서 입력 → `data/assistant/anthropic_api_key`(권한 600). 환경변수 `ANTHROPIC_API_KEY` 가 있으면 그걸 쓴다. 키는 다시 표시하지 않는다.
- **LLM 호출**: 공식 Java SDK `com.anthropic:anthropic-java:2.34.0` 추가(레포 규칙상 의존성 최소지만, Claude 호출은 공식 SDK 사용 원칙을 따름).
  수동 도구 루프, 응답(Message)을 그대로 다음 요청에 붙여 thinking 블록 보존, system·도구 정의에 프롬프트 캐시,
  effort `medium`, 거절 시 서버 측 대체 모델(`fallbacks: "default"`, 베타 `server-side-fallback-2026-07-01`). 강제 tool_choice 는 쓰지 않는다(Sonnet 5.5 는 400).
- **max_tokens**: 문서의 2000 대신 8000. Sonnet 5.5 는 thinking 이 항상 켜져 있고 thinking 도 max_tokens 에 포함돼, 2000 이면 도구 호출 전에 잘릴 수 있다. 타임아웃은 문서대로 60초.
- **프로세스 분리**: 별도 프로세스·advisory lock 대신 같은 앱 안의 스레드 두 개. timing worker(`@Scheduled`, turn 제외)와 agent worker(전용 스레드, turn 만 priority desc·fire_at asc 로 하나씩).
  LLM 을 기다리는 동안 락을 잡지 않고 도구마다 따로 트랜잭션이라 개입이 밀리지 않는다(S9 테스트).
- **turn id**: `POST /v1/messages` 가 turns 행을 `queued` 로 먼저 만들고 그 id 를 돌려준다(문서의 202 {turn_id}). turn 작업의 target_id = turn id. 상태 값에 queued·skipped 추가.
- 서버가 turn 실행 중에 재시작되면 그 turn 은 failed 로 둔다(같은 답장이 두 번 가지 않도록).
- `events.start/end` 는 H2 예약어 때문에 `start_at/end_at`, `changes.before/after` 는 `before_value/after_value`.
- 도구 정의(JSON Schema)는 `src/main/resources/assistant/agent/tools.json`. 시스템 프롬프트는 문서 §9 그대로 `assistant/prompts/system.md`.

### 동작 세부
- 확인 카드 문구: 도구에 선택 인자 `confirm_text` 를 두어 LLM 이 사유를 넘긴다. 없으면 자동 문장.
- 확인 카드 수락 시 실행 결과 변경은 trigger=`system` turn 으로 기록한다.
- 권한: upsert_event 의 버퍼·제목만 바꾸는 것은 trigger 와 무관하게 실행(시각 변경·생성만 확인 카드). 목표 생성 시 마감 지정은 "변경"이 아니라 실행.
- 캘린더 출처 일정은 v2 에서 어떤 변경도 거절(v5 에서 buffer 만 허용 예정).
- 목표 취소 시 그 목표의 active 할 일도 취소하고 미래 블록을 취소한다.
- 할 일 완료(status=done): 미래 planned 블록 취소, remaining 0.
- 되돌리기: changes 는 행 전체 스냅샷. "이번 변경에서 바뀐 필드" 중 현재 값이 after 와 같은 것만 before 로 되돌린다. 생성 되돌리기는 취소(메모는 dismissed).
  블록은 되돌릴 모습(before, 생성이면 after)의 시작 시각이 이미 지났으면 손대지 않는다.
- 하루 turn 수는 logical day(기상 기준) 시작 이후 started_at 으로 센다.
- LLM 장애: user_message 면 첫 실패 때만 안내 메시지를 보내고 1분 뒤 재시도(최대 3회). 키·모델 미설정은 재시도 없이 "아직 AI 연결이 설정되지 않았어요" 답장.
- 텍스트만 답한 경우 그 텍스트를 reply 로 보낸다(S10). 거절(refusal)도 같은 경로.
- get_timeline 의 날짜는 활동일(기상~다음 기상). quiet 항목은 활동 구간 안의 방해 금지만 보인다(수면은 하루 앞뒤).
- 목표 없는 할 일 그룹의 마감은 그 할 일들 중 가장 이른 마감.
- progress_minutes_7d: 최근 7일 actual_start 가 있는 블록의 actual_start~actual_end(진행 중이면 now).
- v1 수동 등록 검증을 v2 검증기로 바꿔 식사 시간 겹침도 거절한다.
- `GET /v1/messages` 는 after_id 가 없으면 최근 100개, 있으면 그 뒤 200개까지. 질문 options·question_status, 확인 카드 confirmation_status 를 함께 준다.
- 관리 API 추가: `POST /v1/admin/anthropic_key`, `GET /v1/admin/agent`(모델·키 설정 여부·최근 turn).

### 웹 (test.seobin.page)
- AI 연결(키 입력·상태), 웹 채팅(질문 버튼·확인 카드 예/아니오), 시간표(7일)를 추가. 휴대폰 없이도 v2 를 확인할 수 있다.

### 안드로이드
- 메인을 하단 탭 2개(채팅·시간표)로 바꿨다. v1 할 일 목록 화면은 시간표 탭으로 대체하고 [+ 직접 추가]로 v1 수동 등록을 유지. 블록을 길게 누르면 수정·삭제.
- FCM 이 없는 기기(임시 토큰)는 채팅 화면이 보일 때만 5초마다 메시지를 다시 읽는다. FCM 이 있으면 문서대로 폴링하지 않는다.
- 채팅 화면을 보고 있을 때는 chat.message 알림을 띄우지 않는다.
- 음성은 `SpeechRecognizer`(ko-KR), 인식 결과를 바로 전송(source=voice). `RECORD_AUDIO` 권한과 RecognitionService queries 추가.
- 위젯은 Glance(`glance-appwidget:1.1.1`) 버튼 하나 → 투명 `QuickInputActivity`(입력창 + 마이크) → source=widget 전송 후 닫힘.
- 평가 세트는 `evals/v2/*.yaml` + `EvalRunner`(JUnit, `-Deval=true` 일 때만) + `Makefile` 의 `make eval`. 채점은 같은 모델로 한 번 더 호출.

## v3 (2026-10-29)

### 구조
- **스키마**: `V4__negotiation_proactive.sql`(스키마 버전 4). `interventions.negotiate_until`·`snoozed_minutes`, `blocks.work_product_id`, `wakeups`·`work_products`·`daily_flags`. jsonb 는 VARCHAR.
- **turn 예약**: timing worker 쪽(개입·wakeup·pulse)도 turn 을 만들어야 해서 `TurnQueue`(루트 패키지)로 분리. 우선순위 user_message·snooze_request 10, 대부분 5, pulse 1.
- **먼저 말 걸기**: `agent/ProactiveService` — pulse·morning_fallback·evening·wakeup_fire 작업. 앱 시작 때와 설정 저장 때 pulse·아침·저녁 작업을 다시 예약한다(`ensureJobs`). 다음 회차는 실행 때 예약.
- **사실 계산**: `agent/Facts`(get_facts). 맥락에도 같은 값을 넣는다.
- **웹 검색**: Anthropic 서버 측 도구 `web_search_20260209`(SDK `WebSearchTool20260209`, max_uses 5). 캐시 지점(마지막 사용자 도구) 뒤에 붙여 켜고 꺼도 앞부분 캐시가 깨지지 않게 했다. `pause_turn` 이면 응답을 그대로 붙여 최대 5번 이어서 요청한다.

### 동작 세부
- "진행 중 개입"은 running 과 negotiating 둘 다. 화면 재표시(reshow)는 running 일 때만. negotiating 중에도 expire·suspend 는 그대로 적용.
- [미루기 요청]은 running 개입에만 적용(협상 중 또 누르면 무시). negotiation_timeout 은 expected_version 으로 확인하고, 사용자 메시지로 연장할 때마다 version 이 올라 이전 작업은 skipped.
- get_facts: `snoozed_today_minutes` 는 move_block 이 nudging 블록을 옮길 때 기록한 `interventions.snoozed_minutes`(새 start − 기존 start) 합. 할 일 마감이 없으면 목표 마감을 쓴다.
  `free_minutes_until_deadline` 은 v2 빈 시간 계산과 같되 그 할 일 자신의 블록은 빈 시간으로 센다(그 일을 할 시간이므로). `start_rate_this_hour_7d` 는 모든 할 일의 블록 기준, 없으면 null. `reason` 은 목표 이유, 없으면 할 일 메모.
- start_now: 길이 = min(남은 분, 다음 planned 블록 길이 또는 60), 최소 5분. 일정(버퍼 포함)·조용한 시간·started 블록과 겹치면 거절, planned·nudging 은 취소하고 displaced 로 돌려준다. 식사는 검사하지 않는다(문서대로).
- extend_block: 늘어나는 구간(max(지금 끝, now) ~ new_end)만 검증기의 겹침 검사(블록·일정·조용한 시간·식사)를 한다. end_block 은 started 만, 할 일 완료는 upsert_task 로 따로.
- 블록 시작 개입 때 이전 블록이 started 면 partial(actual_end=now), 그 블록의 block_end 작업도 취소.
- block_missed 는 expire(no_response)·suspend(quiet)·block_end 가 planned 블록을 missed 로 만들 때(never_started). 새 개입에 밀려(superseded) missed 가 되는 경우는 트리거하지 않는다.
- **[시작] 시 block_started turn 추가**: 문서 §2 에는 없지만 §6 "작업이 시작되면 schedule_wakeup 으로 체크인"을 하려면 비서가 깨어날 계기가 필요하다. `start` 액션(개입 화면·관리 화면)으로 블록이 시작되면 block_started turn(priority 5). start_now 도구는 이미 turn 안이라 만들지 않는다.
- silent 결정(§4.5)의 "오늘 보낸 non-reply 메시지"는 kind 가 proactive·briefing·checkin 인 비서 메시지(question·confirmation 제외). high 사용 수는 그중 importance=high 이고 알림이 간 것. "일정 진행 중"은 버퍼 제외.
- chat.message 푸시에 `channel`(chat|checkin) 추가. silent 여도 푸시는 보낸다(앱이 알림 없이 채팅만 갱신).
- pulse 의 "직전 30분 안에 다른 turn"은 queued·skipped 가 아닌 turn 의 started_at 기준. agent worker 가 pulse turn 을 집었을 때 시각이 된 다른 turn 이 대기 중이면 skipped.
- morning: first_unlock 이 오늘 활동 구간(기상~취침) 안일 때만(수면 중 새벽 확인은 무시). 하루 1회는 daily_flags. wakeup 이 2시간 넘게 늦거나 할 일이 active 가 아니면 skipped.
- 작업물은 soft 상한을 넘으면 생성 거절. attach 는 planned·nudging 블록만. plan.sync upcoming 에도 work_product_id 를 넣어 로컬 백업 개입도 작업물을 연다. plan.sync 에 `negotiation_timeout_minutes` 추가.
- send_message 의 kind 에 briefing·checkin 추가(intervention 은 v4 용으로 코드만 받는다, 도구 정의에는 없음).
- [미루기 요청] turn 에서 LLM 이 텍스트로만 답하면 그 텍스트를 reply 로 보낸다.
- v1 T14·T7 은 v3 §3.5(변경)에 맞춰 "끝 시각에 started 유지 + block_ended turn"으로 고쳤다.

### 관리 화면
- 진행 중 개입에 "협상 중 · HH:MM까지" 표시와 [미루기 요청] 보내기 버튼. "먼저 말 걸기·작업물" 칸: 오늘 메시지 예산·high·turn 수, 예약된 wakeup, 아침·저녁 완료, 작업물(눌러서 본문·출처). 채팅에 🔕(알림 없음)·high 표시, 시간표에 작업물 버튼.
- API 추가: `GET /v1/work_products/{id}`(문서 §7), `GET /v1/admin/proactive`(관리 화면용). admin/status 의 개입에 status·negotiate_until.

### 안드로이드
- 개입 화면 [시작] 아래 작은 [미루기 요청] → snooze_request 액션, 화면 닫고 채팅 탭, 상단 배너 "협상 중 · 답이 없으면 N분 뒤 다시 알려요".
- FCM 이 없을 때도: 협상 시간 + 30초 뒤 로컬 타이머가 `GET /v1/admin/status` 로 개입 상태를 보고 running 이면 화면, negotiating 이면 1분 뒤 다시, 없으면 끝낸다. 서버에 못 닿으면 화면을 띄운다(안전한 쪽).
- 작업물 뷰어 `WorkProductActivity`: Markwon(`io.noties.markwon:core:4.6.2`, 의존성 추가 — 문서 §8.2 지정)을 AndroidView(TextView)로, 아래 출처 링크. [시작] 때 work_product_id 가 있으면 바로 열고, 채팅의 "작업물#번호"는 버튼으로 연다(프롬프트에 그렇게 적게 함).
- 알림 채널 chat·checkin 분리. silent 메시지는 알림 없이 채팅에만(🔕).
- first_unlock: `ACTION_USER_PRESENT` 는 매니페스트 리시버로 받을 수 없어 앱 프로세스가 살아 있을 때 런타임 리시버로 받아 하트비트 events 로 보낸다. 못 받은 날은 서버 fallback(기상+60분).
- Room 스키마 3(파괴적 마이그레이션 — 로컬 개입·계획은 다음 동기화로 다시 받는다). versionCode 3 / 3.0.0.

### 평가
- `evals/v3/*.yaml` 8개(§9.2). EvalRunner 가 `-Deval.dirs=v2,v3`(기본 둘 다)로 폴더를 고르고 `trigger`·`inputs`·`setup.blocks[].status/intervention`·`setup.sql`·`setup.memory` 를 지원한다. 결과 요약에 wakeup·작업물·개입·memory·알림 여부를 넣었다.

## v3.1 (2026-10-29) — 블록 통일·목표 관리·풀스크린 대화

### DB·백업 (§2.1)
- **DB 확인**: H2 **파일** DB(`jdbc:h2:file:./data/assistant/assistant`, 서버 `/root/data/assistant/assistant.mv.db`). 재시작해도 데이터가 남으므로 PostgreSQL 전환은 하지 않았다.
- **이전 전 백업**: `AssistantDb` 가 스키마를 올리기 직전(기존 버전 > 0 이고 새 버전이 있을 때) H2 `BACKUP TO` 로 `{data-dir}/backups/pre-V{n}-{시각}.zip` 을 만든다. 실패하면 마이그레이션하지 않는다. 메모리 DB(테스트)는 건너뛴다.
- **매일 백업**: 서버 cron 대신 앱 작업 `db_backup`(`BackupService`) — 매일 04:00(사용자 시간대) `daily-*.zip`, 7일 지난 daily 는 지운다. pre-V* 는 지우지 않는다. 관리 화면 "DB 백업" 에 목록.
- **스키마 V5**: blocks(kind·title·goal_id·task_id null 허용·lead_minutes·buffer_after_min·location·source·external_id·sensitive·migrated_event_id), goals(label·color·strategy_summary·progress(JSON 문자열)·waiting_until·waiting_reason·priority), messages.intervention_id, interventions.negotiate_until→talk_until·negotiating→talking, data_migrations.
- **데이터 이전**: `V5DataMigration` 이 앱 시작 때마다 실행(다시 실행해도 같은 결과). 중복 판별은 `blocks.migrated_event_id`. events 는 남겨 두고 더 쓰지 않는다(다음 버전 삭제). 결과는 로그·`data_migrations`·관리 화면.

### 고정·계획 블록 (§1, §3.1~3.3)
- 블록 금지 구간 = [start − lead, end + buffer_after]. 계획 블록 검증은 유지 블록의 금지 구간과 비교한다(계획 블록은 lead·buffer 0).
- **고정 블록 검증**: 사용자가 정한 시각이므로 수면·식사는 검사하지 않는다(길이 5분 미만·이미 지남은 거절). 수면·방해 금지와 겹치면 알람이 안 뜨므로 도구 결과에 warning 을 넣어 비서가 알리게 했다. `/v1/admin/tasks`(v1 호환)는 예전처럼 수면·방해 금지를 거절한다.
- 고정끼리 겹침 거절 문구: "○○(MM/dd HH:mm–HH:mm, 블록#N)와 겹쳐요. 시간을 바꿔 주세요."
- 진행 중 블록과 겹치면 `block_cutoff` 작업(금지 구간 시작)으로 partial + block_ended turn(reason=fixed_block).
- (개정 3에서 `fixed.default_lead_minutes` 를 없앴다. 아래 "v3.1 개정" 참고.) v1 `/v1/admin/tasks` 는 lead 0 고정 블록 + 할 일을 함께 만든다(v1 수정·삭제가 할 일 id 로 동작하도록).
- 고정 블록 move_block: 권한(사용자 말 아니면 확인 카드) 후 고정 블록 규칙으로 옮긴다(겹친 계획 블록 displaced). nudging 이면 snoozed(moved).
- 고정 블록 화면 기본 문구: "곧 {title}이에요. {lead}분 뒤 시작이라 지금 출발 준비하세요."(lead 0 이면 "곧 {title}이에요.") — OPEN_ISSUES 참고.
- "고정 블록 진행 중"(구 일정 진행 중: pulse 건너뛰기·알림 없음 판단)은 [시작]을 누르지 않은 고정 블록만. 시작한 고정 블록은 작업 중으로 본다(체크인 알림이 감).
- get_history completions·interventions 의 task_id 는 고정 블록이면 null.

### 목표 진행 보장 (§3.4)
- `GoalCoverage`: 미래 블록 = goal_id 가 그 목표인 블록 중 planned·start>now. 누락 = active·대기 아님·now~now+min_horizon_days 안에 미래 블록 없음.
- turn 끝 검사: 이 turn 에서 만들거나 바꾼 목표(upsert_goal, upsert_task 의 목표, 블록을 만들거나 취소·종료한 목표)만 본다. 도구 호출 없이 끝난 응답 뒤에 user 메시지 "[시스템 검사] 목표 #id(label)에 N일 안에 잡힌 블록이 없습니다…" 를 붙여 계속한다(LlmClient.addUserText). coverage_retry 회 뒤에도 누락이면 turn 은 끝내고 warn 로그 + 30분 뒤 goal_needs_blocks.
- 이어 잡기: turn 밖에서 블록이 missed·partial·cancelled 가 될 때(expire·suspend·block_end·새 개입이 이전 started 를 partial·앱에서 고정 블록 삭제) 그 목표가 누락이면 goal_needs_blocks(priority 5). 같은 목표의 대기 중 turn 이 있으면 만들지 않는다. turn 안의 변경은 turn 끝 검사가 맡는다.
- 매일: 맥락에 "블록이 없는 목표" 섹션을 항상 넣는다. 아침·저녁 작업이 왔는데 이미 처리된 날이면 누락 목표마다 goal_needs_blocks.
- waiting_until 을 쓰면 `goal_waiting_check` 작업을 그 시각에(바뀌면 다시) 예약 → 그때도 대기가 끝났고 active 면 goal_waiting_check turn.
- 목표 끝내기(탭 [완료]·[취소], 도구 cancel goal, upsert_goal status=done)는 같은 처리: 할 일 done/cancelled, 미래 계획 블록 cancelled, 연결 고정 블록은 goal_id 만 비움, 진행 중 개입이면 cancelled.

### 목표 이름표·색 (§3.5)
- 팔레트 8색: #E64980 #4C6EF5 #12B886 #FAB005 #7950F2 #FD7E14 #15AABF #82C91E. active 목표가 쓰지 않는 첫 색, 모두 쓰면 (active 수 % 8).
- label 은 6자(코드포인트)로 자른다. 비면 제목 앞 6자. 탭에서 색은 #RRGGBB 로 받아 대문자로 저장.

### 풀스크린 대화 (§3.6)
- `POST /v1/messages` 에 intervention_id 가 있으면 그 개입의 대화: 메시지 저장(intervention_id, source fullscreen|fullscreen_voice) → 개입이 끝나지 않았으면 talking·talk_until·talk_timeout(expected_version) → intervention_talk turn(priority 10). 개입이 이미 끝났어도 turn 은 만든다(사용자가 말했으므로).
- intervention_talk 은 권한상 user_message 와 같다(`Permissions.fromUser`). 맥락에 그 개입의 대화와 블록 할 일의 사실(get_facts)을 넣는다.
- 답장: intervention_talk turn 의 send_message(kind=reply)와 텍스트만 답한 경우 모두 intervention_id 를 붙인다. chat.message 푸시에 intervention_id·text(최대 1000자).
- start_now 에 block_id 를 추가(고정 블록처럼 할 일 없는 블록의 개입을 시작 처리).
- 구 negotiation_timeout 작업과 구 snooze_request 액션은 호환 처리(대화 시작 "미루고 싶어요").

### 목표 탭·고정 블록 API (§3.7, §4.4)
- `GoalController`: GET /v1/goals(활성 + 최근 30일 끝난 목표, load_status·next_block_start·upcoming_block_count·coverage), GET /v1/goals/{id}/blocks, PATCH, complete, cancel. 변경은 먼저 goal_changed_by_user turn 을 만들고 그 turn_id 로 changes 에 남긴다.
- `/v1/fixed_blocks` POST·PATCH·DELETE: source=manual, displaced 가 있으면 blocks_displaced turn. 계획 블록 수정 API 는 없다: 앱에서 계획 블록을 길게 누르면 비서에게 채팅으로 요청한다(비서가 다시 배치).

### 앱 3.1.0
- 탭 3개(채팅·시간표·목표). 시간표: 목표 색 띠·이름표 칩·'고정'·출발 시각. [+] = 고정 블록 만들기(제목·날짜·시각·길이·출발 준비 기본 10·장소·목표·첫 행동).
- 풀스크린: 이름표·제목·문구 / [시작] / 대화 / 입력줄(🎤 ko-KR 인식 끝나면 바로 전송). 답을 기다리는 동안 "생각 중…", 3초마다 서버에서도 확인(FCM 이 늦어도 보이게). 답은 TTS. [미루기 요청] 버튼은 없앴다. talk_timeout 뒤 intervention.level 이 다시 오면 같은 화면에서 알림음.
- 로컬 백업 알람은 plan.sync 의 alarm_at(= start − lead) 기준. Room 스키마 4(파괴적 마이그레이션).

## v3.1 개정(빌드문서 v3.1 개정 3, 2026-10-30) — 기본 식사 시간 폐지·준비 시간은 비서의 판단

- **기본 식사 시간 폐지**: 설정 `life.meals` 기본값·검증·"식사 시간 겹침" 검사·시간표 meal 항목·맥락의 식사 표기를 모두 없앴다. 서버 DB 의 `life.meals` 행은 V6 마이그레이션이 지운다(직전 자동 백업 pre-V6). 점심 시간에도 블록이 잡힐 수 있다. 비워 둘 시간은 사용자가 고정 블록으로 넣는다. 집중 시간대 등 다른 설정 기본값은 그대로(문서 §0-6).
- **준비 시간 기본값 없음**: `fixed.default_lead_minutes` 설정을 없앴다(V6 가 행 삭제). lead·buffer 를 안 주면 0 이고 `prep_set_by`=NULL(아직 안 정함, 시작 시각에 개입).
- **prep_set_by**(blocks, V6): 앱 API(`/v1/fixed_blocks`, `/v1/admin/tasks`)에서 lead·buffer 를 넣으면 user, 비서 도구가 넣으면 assistant, 도구에서 `prep_from_user=true`(사용자가 직접 말한 값)이면 user. 이미 lead·buffer 가 있던 고정 블록(일정에서 옮긴 것)은 assistant 로 채웠다.
- **덮어쓰기 금지**: prep_set_by=user 인 블록의 lead·buffer 를 비서가(어떤 trigger 든, prep_from_user 없이) 다른 값으로 바꾸려 하면 도구가 거절한다.
- **확인 카드 없이**: lead·buffer 만 바꾸는 upsert_fixed_block(id + 두 값)은 사용자 말이 아닌 trigger 에서도 바로 실행한다(Permissions 의 시각 변경 = start·end 만). 시작·끝 변경·생성은 기존대로 확인 카드.
- **fixed_block_added**: 앱에서 lead·buffer 없이 고정 블록을 만들면 turn(priority 10, ref block_id). 맥락의 시간표에 "(준비 시간 미정)"·"(준비 시간 사용자가 정함)" 표기.
- 화면 문구 기본값: lead>0 "곧 {title}이에요. {lead}분 뒤 시작", 0 "지금 {title} 시간이에요".
- 앱 3.1.1: [+] 의 출발·준비 시간은 비워 둘 수 있다(비우면 비서가 정함). 관리 화면도 같다.

## 모델 선택·FactChat 연결 (2026-10-31, 사용자 요청)

- 제공자·모델은 설정 `llm.provider`(anthropic | factchat)·`llm.model`(빈 값 = `AGENT_MODEL`)에 둔다. 관리 화면 "AI 연결"에서 바꾸고 다음 turn 부터 적용된다(재시작 불필요). 키는 제공자별 파일 `data/assistant/{제공자}_api_key`.
- FactChat(영남대 크레딧): `claude-*` 모델은 Anthropic 공식 Java SDK 에 baseUrl `https://factchat-cloud.mindlogic.ai/v1/gateway/claude` 로 보낸다. 그 밖의 모델(GPT·Gemini 등)은 OpenAI 호환 Chat Completions(`…/v1/gateway/chat/completions/`)를 JDK HttpClient 로 직접 부른다(의존성 추가 없음, `OpenAiCompatLlm`).
- 인증: 게이트웨이는 문서상 x-api-key 지만 실제로 Bearer 를 요구해서 둘 다 보낸다. 가짜 키로 두 경로 모두 "Invalid API key"(401)까지 도달하는 것을 확인했다.
- 게이트웨이에서는 Anthropic 전용 기능을 끈다: 서버 측 대체 모델(fallbacks), 웹 검색. 공식 API 에서도 effort·웹 검색·fallbacks 는 지원 모델에만 붙인다(구형 모델 400 방지).
- OpenAI 호환 경로: 도구는 function 으로 옮기고, 받은 assistant 메시지(tool_calls 포함)를 그대로 되돌려 보낸다(Gemini thought signature 보존). 출력 토큰 상한은 보내지 않는다(모델마다 파라미터 이름이 달라 400). 프롬프트 캐시 지정이 없고 cached_tokens 만 센다.
- 모델 목록은 `GET …/v1/gateway/models/`(관리 화면 [모델 목록 불러오기]). [연결 테스트]는 도구 없이 짧은 요청 하나를 보낸다.
- turns 의 토큰은 제공자와 무관하게 같은 칸에 쌓는다(모델별 단가는 다름).

- 앱 3.1.2: 설정 화면에 "AI 모델" 칸(제공자 선택·모델 입력/목록에서 고르기·모델 저장·연결 테스트·제공자별 키 저장). 관리 화면과 같은 서버 API(`/v1/admin/agent`, `/v1/admin/llm`, `/v1/admin/llm_key`, `/v1/admin/llm/models`, `/v1/admin/llm/test`)를 앱의 API 토큰으로 부른다.
- 앱 3.1.3: 홈 버튼으로 앱을 나가면 비서 알림이 안 뜨던 문제 수정. 채팅 화면이 "보는 중" 표시를 화면이 사라질 때만 껐는데, 홈으로 나가도 화면은 사라지지 않아 FCM 을 받고도 알림을 건너뛰었다(서버 발송은 정상이었음). 이제 앱이 화면에 보일 때(ON_RESUME~ON_PAUSE)만 "보는 중"이다.

## v3.2 (빌드문서 v3.2, 2026-11-02) — 마감·상태 판단·반복 고정 일정·달력·메모·블록 기록·연결 블록

- **복귀 지점**: 작업 전 운영 상태(`496d8f7`)에 태그 `1102_4_v3.2전`. 새 DB 컬럼은 모두 NULL 허용·기본값이라 코드만 되돌려도 v3.1 코드가 V7 DB 로 동작한다(AssistantDb 는 모르는 상위 버전을 건너뛴다). DB 자체는 마이그레이션 직전 자동 백업 `backups/pre-V7.zip`.
- **DB V7**: `fixed_series`(H2 예약어라 `interval` → `interval_n`, 요일은 `by_days` "1,3" 문자열), `series_exceptions`(action skip | moved | **conflict**), `blocks.series_id·occurrence_date·memo·memo_updated_at·linked_fixed_block_id`(+ unique(series_id, occurrence_date)), `block_logs`, `turns.stage·stage_text·queued_at·updated_at·tool_names·duration_ms`. 설정 `series.horizon_days`(14)·`lookahead.days`(28)·`memo.urgent_hours`(24)·`memo.max_chars`(1000).
- **P0 turn 종료**: 응답에 send_message·ask_user 가 있고 그 응답의 도구가 모두 성공했으며 조회 도구(get_*)가 없으면 turn 을 끝낸다(문서 Z1: 답장 + 기록 도구를 한 응답에서). 실패한 도구가 있거나 조회가 섞이면 결과를 돌려주고 계속한다. 목표 누락이면 결과와 [시스템 검사] 문장을 함께 돌려보낸다(turn 계속).
- **P0.2 공통 마무리**: `AgentRunner.finish` 한 곳(텍스트 답 → 목표 누락 정리 → '저장됨' 줄 → turn 기록). 실패·재시도·건너뜀도 `endTurn` 한 곳에서 stage(done | failed | queued)를 정한다.
- **'저장됨' 한 줄**: 문서는 "turn 의 changes 를 보고"지만, 반복 일정처럼 changes 에 남지 않는 변경이 있어 도구가 실행될 때 `Tools.Context.savedLines` 에 모으고 turn 끝에 kind=system·알림 없음으로 한 메시지로 보낸다(user_message·intervention_talk 만). 형식 `저장됨: 과제 · 마감 11/7(토) 23:00`, `저장됨: 고정 · 11/5(목) 15:00 치과`, `저장됨: 고정 삭제 · …`, `저장됨: 반복 · 매주 화·목 19:00 헬스`.
- **마감 검증**: 할 일 deadline 이후에 끝나는 계획 블록은 set_schedule·move_block 모두 거절("과제 마감(11/2(월) 12:00) 이후에 끝나는 블록입니다").
- **지금 상태**: 오늘 = logical day(기상부터). 제시간 = 실제 시작 ≤ 예정 + 10분. 미루기 = 오늘 끝난 snoozed 개입 수. 연속 제시간은 시작 시각이 지난 블록(planned·nudging·cancelled 제외)을 최근부터 센다.
- **반복 일정**: 매일 04:10(작업 `series_generate`)과 시리즈 생성·수정 때 오늘~+14일 회차를 만든다. 다른 고정 블록과 겹치는 날은 만들지 않고 `conflict` 예외로 남겨 다시 시도·알림하지 않는다(사용자 메시지 1회, kind=system). 회차 블록 source=`series`.
  - '전체'·'이후 전부'로 다시 만들 때 미래 회차(planned, 시작 전)는 status=cancelled·occurrence_date=NULL 로 지우고 새로 만든다(unique 때문). '이 날만' 옮긴 회차(moved 예외)는 지우지 않고 유지, '이후 전부'면 그 예외를 새 시리즈로 옮긴다. '이번만' 메모와 연결 블록은 같은 날짜의 새 회차로 옮기고, 시각이 바뀌었으면 fixed_changed.
  - 시리즈 메모·이번만 메모는 앱만 쓴다. 비서 도구(upsert_fixed_series)의 memo 는 무시, 채팅 요청은 append_fixed_memo.
- **메모 긴급 호출**: 곧(24시간 안) 시작하는 고정 블록 메모가 바뀌면 `fixed_memo_changed` turn 을 now+2분에 예약하고, 같은 블록 turn 이 대기 중이면 새로 만들지 않는다(2분 디바운스). 반복 '매번' 메모는 가장 가까운 회차 기준.
- **맥락 메모**: morning = 앞으로 28일 고정 일정 전부(아직 블록이 없는 반복 회차 포함, 메모 없으면 '메모:' 없이), 고정 블록 관련 turn = 그 블록 메모·고지, 그 밖 = 48시간 안 메모 있는 일정만.
- **블록 기록**: 비서 add_block_log 는 200자 넘으면 거절(문서 L2 "거절 또는 잘림" 중 거절). 코드 기록은 500자에서 자른다. 연장(extend_block)은 moved 로 남긴다.
- **연결 블록 변경 알림**: 연결된 미래 계획 블록이 있을 때만 fixed_changed(앱·도구·반복 재생성 모두, 대기 중이면 중복 없음). 고정 삭제 시 연결 블록과 그 할 일을 코드가 취소한다.
- **앱 API**: `GET /v1/calendar`(최대 62일), `GET/PATCH /v1/tasks/{id}`(PATCH 는 task_changed_by_user turn, priority 10), `GET /v1/turns/{id}/status`, `GET /v1/blocks/{id}`·`/logs`, `/v1/fixed_series` CRUD(scope·from_date 는 쿼리). `PATCH /v1/fixed_blocks/{id}` 에 memo 만 있으면 메모만 저장(시작한 블록도 가능).
- **앱 3.2.0**: 시간표 탭 → 달력(월·주·일, 시간 축 0~24시, 7시로 스크롤). 채팅은 열 때 최신, 맨 아래면 따라가고 위면 [↓ n]. 보낸 메시지마다 turn 진행 상태를 1.5초마다 읽어 자리 표시(점 + 문구, 20초 넘으면 "(조금 오래 걸리고 있어요)"), 실패면 [다시 시도], 뒤에 보낸 것은 "대기 중". 개입 화면에 메모, "생각 중…" 을 진행 문구로. Room 스키마 5(파괴적).

## 목표 마감 처리 (2026-11-02, 사용자 지시 + 검토 수정안) — 앱 3.2.1

- **복귀 지점**: 태그 `1102_5_목표마감수정전`(= `3a3939f`). DB V8 은 NULL 허용 칸만 추가해 코드만 되돌려도 동작한다.
- **검증기**: 계획 블록의 마감 기준 = min(할 일 마감, 목표 마감). 고정 블록(validateFixed)·즉시 시작(start_now)은 이 검사를 타지 않는다.
- **목표 블록 검사(GoalCoverage.gap)**: 검사 범위 = 지금 ~ min(지금+2일, 목표 마감). 블록이 없을 때 마감 지남 → DEADLINE_PASSED, 할 일이 있었는데 모두 done → TASKS_DONE(처음부터 할 일이 없던 새 목표는 MISSING 그대로), 마감까지 5분 이상 이어진 빈 시간이 없으면 → NO_TIME(14일까지만 본다), 그 밖 MISSING. MISSING 만 '블록 없음'.
- **질문은 코드가 한 번만(GoalPrompts, LLM 없음)**: 메시지 kind `goal_deadline`·`goal_shortage`·`goal_done_check` + messages.goal_id. 기록 칸 goals.`deadline_prompted_at`·`deadline_reminded_at`·`shortage_prompted_at`·`done_prompted_at`. 마감이 바뀌면(비서 도구·앱 목표 탭) 마감 관련 기록을 지우고 마감 작업(`goal_deadline` job, 마감 시각)을 다시 예약. 새 active 할 일이 생기면 done 기록을 지운다. 되돌리기(undo)는 칸을 함께 되돌리지만 마감 작업은 다시 예약하지 않는다(다음 sweep 이 처리).
- **확인 시점**: 마감 시각 작업, 시작할 때, turn 끝(손댄 목표), 아침·저녁 점검.
- **마감 지남 시 '알람 보류' 대신 취소**: 그 목표의 미래 계획 블록을 취소하고(블록 기록 남김) 묻는다. 연장하면 goal_changed_by_user turn 에서 비서가 새 마감 안에 다시 배치한다(보류 상태를 새로 만들면 set_schedule 교체·블록 끝 처리·로컬 알람·되돌리기가 모두 다시 걸 수 있어서).
- **아침 상기 1회**: 마감 지남 질문을 전날 이전에 보냈고 상기하지 않았으면 morning 맥락에 "한 번만 짧게 상기"로 표시하고, morning turn 이 끝나면 상기한 것으로 기록. 다른 turn 에는 "다시 묻지 말 것".
- **배포 시 한 번**(data_migrations `goal_deadline_cleanup_v1`): 마감 지난 목표는 위 질문 흐름, 마감 전인데 마감 뒤에 끝나는 계획 블록이 있는 목표는 `goal_blocks_after_deadline` turn(비서가 마감 안으로 재배치, 목표·마감은 바꾸지 않음). 2026-11-02 운영 DB: 해당 목표 1개(마감 11/2 15:00 지남, 미래 계획 블록 4개).
- **앱 3.2.1**: 채팅의 목표 질문에 버튼([마감 연장]은 날짜·시각 선택기 → 목표 PATCH, [완료]·[취소]는 목표 탭 API, [할 일 더 있어]는 비서에게 메시지). 처리한 질문은 이 기기에서 버튼을 숨긴다. 목표 탭 마감 변경도 날짜·시각 선택기. 달력 주·일 보기를 표준 시간표(요일 머리줄 고정, 시 눈금, 격자선, 색 칸에 제목·시각·메모, 시간 높이 64/76dp, 8시부터, 지금 시각 선)로 바꿨다.

## 앱 업데이트(덮어 설치)와 앱 안 업데이트 (2026-11-03, 앱 3.2.4)

- 디버그 APK 를 CI 러너마다 새로 만든 키로 서명해서 매번 지우고 다시 깔아야 했다. 고정 키를 Secret `ANDROID_DEBUG_KEYSTORE_B64`(keystore 의 base64)로 두고, android.yml 이 빌드 전에 `~/.android/debug.keystore` 자리에 놓는다(별칭 androiddebugkey, 비밀번호 android — 디버그 기본값이라 키 파일 자체가 비밀). 키 원본은 사용자 PC `C:\Users\ssbin\.assistant-signing\debug.keystore`(저장소 밖). **잃어버리면 다시 지우고 깔아야 하므로 따로 백업한다.**
- Secret 이 없으면 예전처럼 임시 키로 빌드하고 서버 업로드를 건너뛴다(경고 annotation).
- 고정 키로 빌드하면 android.yml 이 APK·version.json 을 서버 `/root/data/assistant/app/`에 올린다(기존 SERVER_* Secrets). 서버 `GET /api/assistant/v1/app/latest`·`/app/apk`(API 토큰 필요). 앱은 홈 화면에 돌아올 때마다 확인하고, 새 버전이면 파란 띠 "새 버전 …이 있어요" → 받기 → 설치 화면. 첫 사용 때 '출처를 알 수 없는 앱 설치' 허용 화면이 열린다.
- 고정 키로 바꾼 첫 APK 는 키가 달라 한 번 더 지우고 깔아야 한다. 그 뒤부터 덮어 설치된다.

## v3.3 (2026-11-03) — 방해 허용 정도·진행 중 고정 일정 수정·지도 읽기·문서 받기
복귀: 태그 `1103_1_v3.3전`(1c178e3). DB 는 V9 마이그레이션 직전에 자동 백업(`/root/data/assistant/backups/pre-V9-*.zip`). V9 컬럼은 모두 NULL 허용이라 옛 jar 로 되돌려도 동작한다.
- §1 진행 중 = 고정 블록의 금지 구간 [start − lead, end + buffer]. 판단은 `InterruptPolicy`. 사용자 말(user_message·풀스크린 대화)이 아닌 turn 의 메시지·질문·확인 카드가 대상. dnd 중에는 `messages.held_block_id` 로 저장만 하고(채팅·예산에서 빠짐) 금지 구간 끝(`dnd_release` 작업)에 "'○○' 동안 온 소식" 한 메시지(proactive, 예산 1)로 보낸다. 버튼이 있는 질문·확인 카드·목표 질문은 묶음 뒤에 무음으로 다시 붙인다.
- quiet 은 이전 규칙 유지: [시작]을 누른(started) 고정 블록은 작업 중으로 보고 무음으로 만들지 않는다(체크인이 울리게). allow 는 무음으로 만들지 않는다(수면·예산 규칙은 그대로).
- dnd 중 다른 블록의 개입은 시작하지 않는다(그 고정 블록 자신의 시작 화면은 뜬다).
- 앱에서 고정 일정을 만들 때 방해 허용을 비워 두면 null(quiet 로 동작). 그것만으로 turn 을 만들지 않는다(비용). 비서가 다음에 볼 때 `set_interrupt_level` 로 정한다(맥락에 `[고정]` 으로 보임, 정했으면 `[고정·방해금지]` 등). 앱 편집 화면은 값을 바꿨을 때만 보낸다.
- §2 "진행 중"으로 고치는 경우: [시작]을 누른(started) 블록, 또는 시작 시각이 지난 블록에서 시작 시각을 바꾸지 않는 요청. 시작 화면이 뜬 채 시작 시각이 지난 블록을 미루는 것(move_block)은 기존 미루기 그대로다. started 블록의 extend_block·end_block 은 기존 경로(검증기·결과)를 쓰고, 고정 블록이면 사용자 말이 아닐 때 확인 카드가 된다.
- 진행 중 끝 시각 변경: version +1, block_end·진행 중 개입 만료를 새 끝으로 다시 예약, 반복 회차면 series_exceptions moved, 늘린 구간의 계획 블록은 밀어내고 blocks_displaced turn(앱 조작일 때).
- §3 카카오 엔드포인트(공식 문서 developers.kakao.com/docs/ko/kakaomap/rest-api 확인, 2026-11-03):
  - 장소 `GET dapi.kakao.com/v2/local/search/keyword.json?query&x&y&radius`(집 좌표 기준 20km 우선, 없으면 전체), 주소 `/v2/local/search/address.json`
  - 대중교통 `GET dapi.kakao.com/v2/routing/publictraffic?start_x&start_y&end_x&end_y` → `routes[0].properties.totalTime`(초)·`totalDistance`(m)
  - 도보 `GET dapi.kakao.com/v2/routing/walk` → `route.properties.totalTime`·`totalDistance`
  - 자동차 `GET apis-navi.kakaomobility.com/v1/directions?origin=lng,lat&destination=lng,lat` → `routes[0].summary.duration`·`distance`
  - 인증 `Authorization: KakaoAK {REST 키}`. 장소·대중교통·도보는 콘솔의 '카카오맵 사용 설정'이 필요하다(꺼져 있으면 403 "disabled OPEN_MAP_AND_LOCAL service" → 관리 화면 "사용 설정 꺼짐"). 2026-11-03 사용자 키로 확인: 자동차는 200, 나머지는 403(사용 설정 꺼짐).
- 키는 관리 화면 '지도 연결'이 `/root/data/assistant/kakao_api_key`(600)에 저장하거나 환경변수 `KAKAO_REST_API_KEY`. 로그에는 오류 종류만 남긴다. 하루 호출 수는 `kakao_calls` 테이블로 센다(장소 검색 포함).
- 이동 시간 출발·도착 이름은 일정 제목(집은 "집"). 반복 시리즈는 시리즈에서 한 번 장소를 찾고 회차에 복사한다. 매일 04:20 앞으로 7일 점검(빠진 것만).
- 이동 불가능 경고는 user_message·morning turn 맥락에만 넣고, 넣는 순간 block_logs notice 로 기록해 한 번만 알린다.
- §4 PDF 처리에 **PDFBox 3.0.6 의존성 추가**(JDK 에 PDF 파서·렌더러가 없다. 스캔본은 쪽을 110dpi JPEG 로 그려 이미지 입력). DOCX 는 의존성 없이 word/document.xml 의 태그를 벗긴다.
- 문서 처리는 `document_process` turn(priority 1, agent worker)이지만 비서 turn 이 아니라 코드 작업이다(하루 turn 수에서 뺀다). 읽기·요약 모델은 설정 `documents.model`(비면 지금 llm.model). 지금 운영 모델(deepseek, OpenAI 호환)은 이미지 입력을 받지 못할 수 있다 → 이미지·스캔본은 실패로 남고 "자료를 읽지 못했어요"가 간다. 이미지까지 읽으려면 관리 화면에서 documents.model 을 이미지 입력이 되는 모델로 정한다(OPEN_ISSUES).
- 채팅으로 올린 자료는 사용자 메시지(파일 카드, `messages.document_id`)만 남기고 user_message turn 은 만들지 않는다. 함께 쓴 글은 document_ready turn 맥락에 들어간다.
- 문서 파일은 `/root/data/assistant/documents/`, 매일 DB 백업 때 `backups/daily-docs-*.zip` 으로 같이 백업(7일).
- 앱 3.3.0(versionCode 17): 고정 일정 칸 🔕/🔔, 상세에 방해 허용·오는 길·가는 길·[지금 끝내기], 편집에 '방해 허용' 선택(진행 중이면 시작 시각 잠금·반복 범위 없음), 설정에 집 주소·이동수단, 목표 상세 '자료', 채팅 📎 첨부·파일 카드.

## 장소 탭 (2026-11-04 사용자 요청, 앱 3.3.1)
- 새 탭 '장소': 앞으로 30일 고정 일정마다 위치 상태·바로 앞 장소(같은 날 앞 고정 일정, 없으면 집)에서 오는 길(분·km·출처)을 보여 준다. [위치 넣기] → 지도(카카오 지도 Android SDK 2.15.2, devrepo.kakao.com)에서 가운데 핀으로 고르거나 위쪽 검색 후보를 누른다. 반복 일정이면 '반복 전체 / 이 날만'을 묻는다.
- 지도에서 고른 위치는 place_source=user. 장소 글자로 다시 찾지 않는다. 장소 글자가 비어 있으면 고른 이름으로 채운다. 이름이 없으면 좌표→주소(coord2address).
- 오는 길 분을 직접 고치면 block_travel source=user 로 저장하고 다시 계산하지 않는다(그 블록 위치를 다시 고르면 지운다). 비서 맥락에 "(사용자가 정함)".
- API: GET /v1/places, GET /v1/places/search?q, PUT /v1/blocks/{id}/place {lat,lng,name?,series?}, PUT /v1/blocks/{id}/travel {direction,minutes}, GET /v1/app/map_key.
- 지도 SDK 는 REST 키가 아니라 네이티브 앱 키를 쓴다. 저장소에 두지 않고 관리 화면 '지도 연결'이 `/root/data/assistant/kakao_native_key` 에 저장, 앱이 지도 화면을 열 때 받아 KakaoMapSdk.init. 콘솔 [플랫폼 → Android]에 패키지 `kr.ssbin.assistant`, 키 해시 `8RIKncx9ROmE1inTU75hGhZ4mh0=`(고정 서명 키 SHA-1 base64) 등록 필요.
- 지도가 실패해도(키 없음·인증 오류) 화면은 열리고 검색 후보로 고를 수 있다. SDK 는 arm64-v8a·armeabi-v7a 만 있다.

## v3.4 (2026-11-05) — 이동 블록(연결 블록, link_role=travel)
복귀: 태그 `1105_1_v3.4전`(0a60132). DB 는 V10 직전 자동 백업(`backups/pre-V10-*.zip`). V10 컬럼은 NULL 허용·기본값이 있어 옛 jar 로 되돌려도 동작한다(이동 블록은 할 일 없는 계획 블록으로 보인다).
- 이동 블록은 `TravelBlocks.sync(날짜)`가 만든다. `TravelService.computeDay` 끝에서 부르므로, 고정 일정 생성·변경·삭제·진행 중 연장·장소 지정·이동 시간 수정·집 주소·이동수단·매일 04:20 점검·반복 회차 생성이 모두 같은 길을 탄다. 디바운스(1분)는 넣지 않고 바로 계산한다(경로는 캐시라 가볍고, 결과는 멱등).
- 출발지 계산에는 이미 끝낸(done·partial) 고정 일정도 넣는다(귀가 블록이 사라지지 않게). arrive 는 아직 시작 전(planned·nudging)인 일정에만 만든다.
- 틈이 1분 미만이면 이동 블록을 만들지 않는다(경고만은 OPEN_ISSUES).
- 알림: 준비·도착·촉박·귀가는 `travel_notice` 작업이 보내는 가벼운 알림(채팅 메시지 kind=notice, 알림 있음, 수면·방해 금지 시간에는 무음, 메시지 예산에 안 셈). 출발은 이동 블록의 시작 화면(기존 개입). 이동 블록이 있는 고정 일정은 자기 시작 화면을 예약하지 않는다.
- 도착 알림 시각(B.start)에 이동 블록에서 [시작](출발함)을 눌렀으면 알림 없이 고정 일정을 started 로 둔다. 안 눌렀으면 "지금 ○○ 시간이에요"만 보내고 고정 일정은 planned(끝나면 기존처럼 놓침).
- 이동 블록의 lead_minutes = 고정 일정의 준비 시간(촉박이면 0). 그래서 이동 블록의 금지 구간이 준비 시간까지 덮어 계획 블록이 그 사이에 들어가지 않는다.
- lead 의미 변경: 앞으로 앱·비서가 정하는 lead 는 '이동 전 준비'만. v3.3 이전 사용자 lead 는 `lead_includes_travel=TRUE` 로 표시해 준비 알림을 이동 시작 − (lead − 이동)에 보낸다. 사용자가 앱에서 준비 시간을 다시 정하면 표시를 지운다.
- lead 정리(일회성, data_migrations `travel_lead_v1`): 배포 후 시작 때 앞으로 14일 이동 블록을 먼저 만들고, 이동 블록이 생긴 고정 일정 중 비서가 정한 lead 를 0 + prep_set_by NULL(비서가 아직 안 정함)로. 결과는 서버 로그 `travel lead migration: target=… changed=… skipped=…` 와 data_migrations 에 남는다.
- 비서 도구 move_block·extend_block·end_block·start_now·attach_work_product 가 이동 블록을 가리키면 거절. set_schedule 은 이동 블록을 교체하지 않고 유지 블록으로 본다. 검증기의 고정 블록 검사는 이동 블록을 건너뛴다(고정 일정이 바뀐 뒤 다시 맞춘다). 연결 블록 알림(fixed_changed)에서도 뺀다.
- API: `PATCH /v1/travel_blocks/{id} {minutes|null}`, 달력 블록에 `link_role`·`travel_direction`·`travel_tight_min`·`travel_info`, 고정 블록에 `travel_blocks`(linked_prep 에서는 뺀다). 이동 블록 결과 바꾸기는 거절.
- 앱 3.4.0: 달력 이동 블록 점선·🚌(귀가 🏠 연한 색, 촉박 주황 "N분 부족"), 이동 블록 상세(수단·시간·출처, 이동 시간 고치기·자동으로 되돌리기), 고정 일정 상세 '출발 전 준비(이동 시간 제외)'와 연결된 이동 블록 표시.
