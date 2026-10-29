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
