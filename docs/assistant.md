# 실행 비서 (assistant)

정해진 시각에 휴대폰 화면을 띄워 할 일을 시작시키는 개인용 비서. 안드로이드 앱 + 이 Spring 앱 안의 서버.
현재 v1(강제 화면 확인). 설계 기록은 [DECISIONS.md](DECISIONS.md), 열린 문제는 [OPEN_ISSUES.md](OPEN_ISSUES.md).

## 주소
| 무엇 | 주소 |
|---|---|
| 관리 화면 | https://test.seobin.page/ |
| API 기본 주소(앱에 입력) | `https://test.seobin.page/api/assistant/` |
| 헬스 체크(인증 없음) | https://test.seobin.page/api/assistant/health |

모든 `/api/assistant/v1/**` 요청은 `Authorization: Bearer <API 토큰>`.

## 서버
- 코드: `src/main/java/com/seobin/phonedrop/assistant/`, 스키마 `src/main/resources/assistant/db/`
- 데이터: `/root/data/assistant/` (H2 DB `assistant.mv.db`, API 토큰 `api_token`)
- API 토큰 확인(서버 웹 콘솔): `cat /root/data/assistant/api_token`
- 환경변수(선택, systemd 서비스에 추가)
  | 이름 | 기본 | 설명 |
  |---|---|---|
  | `ASSISTANT_API_TOKEN` | 없음 → 파일 자동 생성 | API 토큰 |
  | `ASSISTANT_PUSH_MODE` | `fake` | `firebase` 이면 실제 FCM 발송 |
  | `ASSISTANT_FIREBASE_CREDENTIALS` | 없음 | 서비스 계정 키 JSON 경로 |
- 배포: master push → 기존 `deploy.yml`. 스키마 마이그레이션은 앱 시작 때 자동. worker 는 앱 안 스레드 하나라 항상 1개.
- 테스트: `./mvnw test` (FakeClock·FakePush·메모리 H2, 빌드문서 T1~T15)

## 안드로이드 앱 (`android/`)
- 빌드: `android/**` 를 master 에 push 하면 GitHub Actions "Android APK" 가 빌드한다.
  Actions 실행 화면(로그인 필요) 하단 Artifacts 의 `assistant-debug-apk` 를 받아 압축을 풀고 설치.
- 첫 실행: 서버 주소(기본값 그대로) + API 토큰 입력 → 권한 화면에서 4개 모두 초록색으로.
- FCM(google-services.json)이 없으면 블록 시작 90초 뒤 앱이 스스로 화면을 띄운다(로컬 백업).

## 실기기 확인 순서 (빌드문서 v1 §11)
1. 앱 설치 → 권한 화면에서 전부 초록색
2. 관리 화면 또는 앱 [추가]로 2분 뒤 블록 등록 → 화면 끄고 대기 → 잠금화면 위에 비서 화면 + 음성
3. 화면을 닫음 → 3분 뒤 다시 뜸
4. [시작] → 더 안 뜸
5. 서버 연결 없이(비행기 모드) 블록 시각을 기다림 → 90초 뒤 앱이 스스로 화면을 띄움
6. 휴대폰 재부팅 후에도 5번이 동작
