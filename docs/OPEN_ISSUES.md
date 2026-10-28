# 실행 비서 — 열린 문제

## 사용자 준비 필요
- **Firebase 미준비 (v1 §1)**: 패키지 `kr.ssbin.assistant` 의 `google-services.json` 과 FCM 서비스 계정 키가 없다.
  지금은 서버 `PUSH_MODE=fake`, 앱은 FCM 없이 로컬 백업 알람(블록 시작 + 90초)과 15분 주기 동기화로만 동작한다.
  그래서 실기기 확인 2번(정시 화면)은 90초 늦게, 3번(닫으면 3분 뒤 재표시)은 앱 로컬 타이머로만 확인된다.
  준비되면:
  1. `google-services.json` 을 `android/app/` 에 두고(커밋 금지, .gitignore 됨) APK 를 다시 빌드 — CI 빌드에 넣는 방법은 별도로 정해야 한다(Secrets 사용은 사용자 승인 필요).
  2. 서비스 계정 키를 서버 `/root/data/assistant/firebase.json` 에 두고 systemd 서비스에
     `ASSISTANT_PUSH_MODE=firebase`, `ASSISTANT_FIREBASE_CREDENTIALS=/root/data/assistant/firebase.json` 환경변수 추가.

## 문서와의 차이(확인 필요)
- 문서 §3·§9 의 Python/Docker 스택 대신 Spring 앱에 구현했다(사용자 선택). v2~v5 문서도 Python 모듈 이름(`agent_worker`, `context.py` 등)을 전제하므로 같은 구조를 Java 패키지로 옮겨 구현할 예정.
- 문서 §6.6 "nudging → 아무것도 안 함"과 §6.4 expire 사이: 블록 끝 + 15분 유예 동안 개입이 계속된다. 의도대로 구현했다.

## 남은 확인
- 실기기 확인 §11 1~6 은 사용자 확인이 필요하다(APK 설치 후).
- 서버를 끄고 확인하는 5번은 운영 서버를 멈춰야 한다. 대신 휴대폰을 비행기 모드로 두고 확인해도 같은 경로(로컬 백업)를 탄다.
