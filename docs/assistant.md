# 실행 비서 (assistant)

채팅으로 말하면 Claude 가 일정·할 일·목표를 정리하고 시간표를 짜고, 정해진 시각에 휴대폰 화면을 띄워 시작시키는 개인용 비서.
안드로이드 앱 + 이 Spring 앱 안의 서버. 현재 **v3(협상·동행·먼저 말 걸기·대신 처리)**.
설계 기록은 [DECISIONS.md](DECISIONS.md), 열린 문제는 [OPEN_ISSUES.md](OPEN_ISSUES.md).

## 주소
| 무엇 | 주소 |
|---|---|
| 관리 화면(웹 채팅 포함) | https://test.seobin.page/ |
| API 기본 주소(앱에 입력) | `https://test.seobin.page/api/assistant/` |
| 헬스 체크(인증 없음) | https://test.seobin.page/api/assistant/health |

모든 `/api/assistant/v1/**` 요청은 `Authorization: Bearer <API 토큰>`.

## 처음 설정
1. 서버 웹 콘솔에서 API 토큰 확인: `cat /root/data/assistant/api_token`
2. 관리 화면에 토큰 입력 → "AI 연결"에 Anthropic API 키 입력(서버 `/root/data/assistant/anthropic_api_key` 에 저장, 다시 표시 안 함)
3. 관리 화면 채팅 또는 앱에서 "금요일까지 보고서 써야 해"처럼 말하기

## 서버
- 코드: `src/main/java/com/seobin/phonedrop/assistant/` (v2 에이전트는 `agent/`), 스키마 `src/main/resources/assistant/db/`,
  시스템 프롬프트 `assistant/prompts/system.md`, 도구 정의 `assistant/agent/tools.json`
- 데이터: `/root/data/assistant/` (H2 DB `assistant.mv.db`, `api_token`, `anthropic_api_key`)
- 스레드 두 개: timing worker(개입 예약 작업, 1초 폴링) / agent worker(LLM turn 을 하나씩). LLM 을 기다려도 개입이 밀리지 않는다.
- v3 비서가 먼저 깨어나는 계기(turn): [미루기 요청](snooze_request), 블록 시작·끝·놓침(block_started·block_ended·block_missed), 예약한 wakeup,
  아침(첫 잠금 해제 또는 기상+60분)·저녁(취침−60분), pulse(2시간마다, 조용한 시간·일정·작업 중·직전 30분 turn 이면 건너뜀).
  알림 여부(silent)는 코드가 정한다: 방해 금지·일정·작업 중이거나 하루 예산(12개)을 넘으면 알림 없이 채팅에만(high 는 하루 3번까지 예외).
  수치는 설정 `negotiation.*`, `pulse.*`, `messages.*`, `wakeup.*`, `agent.daily_turn_soft`, `morning.*`, `evening.*`, `web.enabled`.
- 환경변수(선택, systemd 서비스에 추가)
  | 이름 | 기본 | 설명 |
  |---|---|---|
  | `ASSISTANT_API_TOKEN` | 없음 → 파일 자동 생성 | API 토큰 |
  | `AGENT_MODEL` | `claude-sonnet-5-5` | 채팅 비서 모델 |
  | `ANTHROPIC_API_KEY` | 없음 → 관리 화면에서 저장한 파일 | Anthropic API 키 |
  | `ASSISTANT_PUSH_MODE` | `fake` | `firebase` 이면 실제 FCM 발송 |
  | `ASSISTANT_FIREBASE_CREDENTIALS` | 없음 | 서비스 계정 키 JSON 경로 |
- 배포: master push → 기존 `deploy.yml`. 스키마 마이그레이션은 앱 시작 때 자동.
- 테스트: `./mvnw test` (FakeClock·FakePush·ScriptedLlm·메모리 H2. v1 T1~T15, v2 단위·S1~S12, v3 N·B·W·P·M·A·R)
- 평가 세트: `ANTHROPIC_API_KEY=... make eval` → `evals/report.md` (v2+v3, `-Deval.dirs=v3` 처럼 폴더 선택. 실제 API 비용이 든다)

## 안드로이드 앱 (`android/`)
- 빌드: `android/**` 를 master 에 push 하면 GitHub Actions "Android APK" 가 빌드한다.
  Actions 실행 화면(로그인 필요) 하단 Artifacts 의 `assistant-debug-apk` 를 받아 압축을 풀고 설치.
- 화면: 채팅(메인, 🎤 음성) · 시간표(+ 직접 추가, 길게 눌러 수정·삭제) · 권한 · 설정 · 작업물 뷰어
- 개입 화면 [시작] 아래 [미루기 요청] → 채팅에서 비서와 협상(상단 배너). 답이 없으면 5분 뒤 화면이 다시 뜬다.
- 비서가 준비한 작업물은 [시작] 때 바로 열리고, 채팅의 "작업물#번호"를 눌러도 열린다.
- 홈 화면 위젯 "비서에게 말하기" → 빠른 입력(입력창 + 마이크)
- FCM 은 2026-10-29 부터 켜져 있다(키는 GitHub Secrets·서버에만, 저장소에는 없다. OPEN_ISSUES 참고).
- FCM(google-services.json)이 없으면: 개입 화면은 블록 시작 90초 뒤 로컬 백업으로, 채팅은 채팅 화면이 보일 때 5초마다 갱신.

## 실기기 확인
### v1 (§11)
1. 앱 설치 → 권한 화면에서 전부 초록색
2. 2분 뒤 블록 등록 → 화면 끄고 대기 → 잠금화면 위에 비서 화면 + 음성
3. 화면을 닫음 → 3분 뒤 다시 뜸
4. [시작] → 더 안 뜸
5. 서버 연결 없이(비행기 모드) 블록 시각을 기다림 → 90초 뒤 앱이 스스로 화면을 띄움
6. 휴대폰 재부팅 후에도 5번이 동작
### v2 (§13)
1. 채팅으로 할 일 등록 → 시간표 탭에 블록 → 그 시각에 v1 화면
2. 음성(🎤)·위젯 입력
3. 확인 카드 [예]/[아니오] 동작
### v3 (§10)
1. [미루기 요청] → 채팅 협상 → 5분 방치하면 화면이 다시 뜸
2. 미루기를 받아주면 새 시각에 화면이 뜸
3. 작업물이 붙은 블록에서 [시작] → 작업물이 열림
4. 하루 동안 메시지가 과하지 않음(관리 화면 "먼저 말 걸기" 칸에서 예산 확인)
