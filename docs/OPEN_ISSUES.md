# 실행 비서 — 열린 문제

## 사용자 준비 필요
- **Firebase 미준비 (v1 §1)**: 패키지 `kr.ssbin.assistant` 의 `google-services.json` 과 FCM 서비스 계정 키가 없다.
  지금은 서버 `PUSH_MODE=fake`, 앱은 FCM 없이 동작한다: 개입 화면은 로컬 백업 알람(블록 시작 + 90초), 계획은 15분 주기 동기화,
  채팅은 채팅 화면이 보일 때 5초 폴링. 채팅 답장 알림(화면을 안 보고 있을 때)은 FCM 이 있어야 온다.
  준비되면:
  1. `google-services.json` 을 `android/app/` 에 두고(커밋 금지, .gitignore 됨) APK 를 다시 빌드 — CI 빌드에 넣는 방법은 별도로 정해야 한다(Secrets 사용은 사용자 승인 필요).
  2. 서비스 계정 키를 서버 `/root/data/assistant/firebase.json` 에 두고 systemd 서비스에
     `ASSISTANT_PUSH_MODE=firebase`, `ASSISTANT_FIREBASE_CREDENTIALS=/root/data/assistant/firebase.json` 환경변수 추가.
- **Anthropic API 키 (v2 §1)**: 관리 화면 "AI 연결"에서 입력해야 채팅 비서가 답한다. 입력 전에는 "아직 AI 연결이 설정되지 않았어요"라고만 답한다.
- **평가 세트 실행 (v2 §13 `make eval`)**: 실제 API 비용이 든다. 사용자 승인 후 `ANTHROPIC_API_KEY=... make eval` 로 실행(이 PC 에는 make 가 없으면 `./mvnw test -Deval=true -Dtest=EvalRunner -Dsurefire.failIfNoSpecifiedTests=false`).

- **평가 세트 v3 실행 (v3 §10 `make eval`)**: v2(5개)+v3(8개)는 실제 API 비용이 든다(v2 만 $0.21 → 합쳐 약 $0.5~1 예상, 웹 검색 사용료 별도). 사용자 승인 후 실행.

## 문서와의 차이(확인 필요)
- **v3 block_started 트리거(문서에 없음)**: §6 프롬프트는 "작업이 시작되면 체크인 예약"을 요구하지만 §2 트리거 표에는 블록 시작이 없다. [시작]을 누르면 비서가 깨어날 계기가 없어 `block_started` turn 을 추가했다(블록 수만큼 turn·비용이 는다).
- **v3 first_unlock 수집 한계**: 안드로이드는 잠금 해제(USER_PRESENT)를 앱이 살아 있을 때만 받는다. 앱이 종료돼 있으면 그날은 기상+60분 fallback 으로 아침 브리핑을 한다.
- **v3 협상(FCM 없음)**: FCM 이 없으면 비서의 협상 답장 알림이 오지 않는다(채팅 화면을 열면 5초 폴링으로 보인다). 협상 시간 뒤 재등장은 앱이 서버 상태를 물어 처리한다.
- 문서 §3·§9(v1)·§2.1(v2)의 Python/Docker/별도 프로세스 대신 Spring 앱 안에 구현했다(사용자 선택). 같은 동작을 스레드 두 개로 보장한다.
- v2 §8.2 의 max_tokens=2000 은 Sonnet 5.5(thinking 항상 켜짐)에서 응답이 잘릴 수 있어 8000 으로 했다.
- v2 §1 "AGENT_MODEL 이 비면 서버 시작 시 에러로 멈춘다" — 같은 서버에 파일 전송·개입 기능이 함께 있어, 멈추지 않고 채팅만 비활성화했다.
- v1 §6.6 "nudging → 아무것도 안 함"과 §6.4 expire 사이: 블록 끝 + 15분 유예 동안 개입이 계속된다. 의도대로 구현했다.

## 남은 확인
- 실기기 확인 v3 §10: [미루기 요청] → 채팅 협상 → 5분 방치 시 화면 재등장 / 미루기 수락 시 새 시각에 화면 / 작업물이 [시작] 시 열림 / 하루 동안 메시지가 과하지 않음.
- 실기기 확인 v1 §11 1~6, v2 §13(채팅 등록 → 시간표 → 그 시각 화면, 음성·위젯, 확인 카드)은 사용자 확인이 필요하다.
- 서버를 끄고 확인하는 v1 5번은 운영 서버를 멈춰야 한다. 대신 휴대폰을 비행기 모드로 두고 확인해도 같은 경로(로컬 백업)를 탄다.
