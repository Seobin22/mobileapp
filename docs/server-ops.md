# 서버·도메인 운영 노트

다른 채팅(세션)에서 이어서 작업할 수 있도록, 서버·도메인·인증서 관련해 지금까지 확인하고 바꾼 내용을 정리한다.
마지막 갱신: 2026-11-13

## 현재 상태 요약

| 항목 | 상태 |
|---|---|
| 서버 | DigitalOcean droplet `phonedrop`, IP `139.59.99.165`, Ubuntu 24.04, nginx 1.24 → Spring 앱(8080) |
| DNS | `seobin.page`, 와일드카드 `*.seobin.page` 모두 서버 IP로 연결됨 (사용자가 등록) |
| 인증서 | Let's Encrypt, lineage 이름 `seobin.page`, 포함 도메인 `seobin.page`, `test.seobin.page`, `check.seobin.page`, `admin.seobin.page`, `image.seobin.page`(2026-11-13 추가), 만료 2027-01-31(2026-11-02 재발급) |
| nginx | HTTPS(443)는 모든 Host를 앱으로 넘긴다. HTTP(80)로 `test.seobin.page`·`check.seobin.page`에 접속하면 nginx 404 (HTTPS로만 사용) |
| 서브도메인 | `test.seobin.page` → 실행 비서 관리 화면 (2026-10-29부터, 이전엔 현재 시간 페이지)<br>`check.seobin.page` → 파일 아카이브: GitHub Actions 결과물(Artifact) 날짜별 목록 `static/check.html`, API `/api/check/artifacts` (2026-11-01, 처음엔 연결 확인 페이지)<br>`image.seobin.page` → 이미지 페이지. 로그인 쿠키 때문에 `seobin.page/image.html` 로 자동 이동한다 (2026-11-13) |
| 이미지 (image) | 화면 `seobin.page/image.html`, API `/api/image/`, 패키지 `image/`. 회원 로그인 + 허용 계정만(`application.properties` 의 `image.allowed-users`, 그 외 회원은 403). 데이터 `/root/data/image/<회원 id>/<상품 id>/` (`product.json`, `files/` 원본·커버·상세, `cache/` 미리보기·HEIC 작업용 JPG·영상 미리보기, `tmp/` 업로드 중 조각). 회원당 10GB, 서버 디스크 여유 2GB 미만이면 업로드 거부. 업로드는 8MB 조각(서버 한도 16MB/조각, 파일당 2GB). 무거운 처리(디코딩·ffmpeg)는 한 번에 하나씩. 백업 없음. **커버·상세는 자르기**(기본은 자른 픽셀 그대로, 해상도 유지; 용량은 JPG 품질로만 맞춤. 커버는 "900×900으로 줄이기" 선택 가능, 상세는 "가로 880px로 자르기"를 고르면 자르기 네모 가로가 원본 880px 로 고정되고 높이만 조절 — 상세는 줄이지 않음). 한 장 최대 5000만 화소. **동영상 만들기**(`#slides`): 회원당 작업 공간 하나(`product.json` 의 `type: "slides"`, 상품 목록에 안 나옴), 고른 사진을 Java 로 같은 크기 프레임(첫 사진 비율, 긴 변 최대 1920, 작은 사진은 가운데·검은 여백)으로 만든 뒤 ffmpeg concat + libx264 로 MP4, 뒤에서 만들고 `status` 로 알림. API `GET /api/image/slides`, `POST /api/image/products/{id}/slideshow` |
| 미디어 도구 | 2026-11-13 apt 설치: `ffmpeg`/`ffprobe` 6.1.1(zscale·tonemap·libx264 포함, 영상 분석·장면 캡처·미리보기 변환), `exiftool` 12.76(HEIC 메타데이터), `heif-convert` 1.17.6(HEIC→JPG, 디코더 `libheif-plugin-libde265` 필요 — no-recommends 설치 때 빠져 따로 설치, 시험용 `libheif-plugin-x265`). 서버: CPU 1개, RAM 961MB, 스왑 없음 |
| `admin.seobin.page` | 관리자 페이지 `static/admin.html`, API `/api/admin/` (2026-11-02). 아이디·비밀번호 로그인(세션 쿠키 Secure·SameSite=Strict, 30분). 텍스트 기록 날짜별 보기·개별/날짜 단위 삭제, 아이디·비밀번호 변경. 로그인 5번 연속 실패 시 5분 잠금 |
| 관리자 계정 | `/root/data/admin/account` (아이디 + PBKDF2 해시). 처음 시작할 때 아이디 `admin` 과 무작위 비밀번호를 만들어 `/root/data/admin/initial_password`(600)에 적는다. 관리자 페이지에서 바꾸면 그 파일은 지워진다. 비밀번호를 잊으면 `account` 파일을 지우고 앱 재시작 → 새 initial_password |
| 텍스트 전송 데이터 | H2 파일 `/root/data/texts/texts.mv.db` (실행 비서 DB와 별도, 백업 없음). `GET /texts?date=YYYY-MM-DD`(없으면 오늘, 한국 시간, 메인에서 지운 것 제외), `POST /texts`, `DELETE /texts/{id}`·`DELETE /texts?date=`(누구나, **숨김만**: `deleted_at` 기록). 관리자 페이지는 숨긴 것까지 모두 보이고(점선 표시), 관리자 삭제만 영구 삭제 |
| 회원 계정 | H2 파일 `/root/data/users/users.mv.db` (다른 DB와 별도, 백업 없음, 2026-11-02). 화면 `seobin.page/account.html`(회원가입·로그인·비밀번호 변경·로그아웃), API `/api/users/` (`signup`, `login`, `logout`, `me`, `password`). 아이디는 소문자 시작 `[a-z][a-z0-9_]{2,19}`(입력은 소문자로 바꿔 저장), 비밀번호 8~100자, PBKDF2 해시. 아이디별 5번 연속 실패 시 5분 잠금, 회원가입 전체 시간당 30개 제한. 관리자 계정과는 별개 |
| 개인 저장소 | `seobin.page/private.html`, API `/api/private/files`. 회원(`data/users/`) 로그인 후 본인 파일만 `/root/data/private/<회원 id>/` 에 저장. 회원당 1GB, 파일당 50MB. 공개 `uploads/` 와 별개 (2026-11-04) |
| 파일 아카이브 토큰(선택) | 없으면 다운로드 버튼이 GitHub 화면으로 연결(로그인 필요). `/root/data/check/github_token` 파일(권한 600) 또는 환경변수 `CHECK_GITHUB_TOKEN` 에 `actions:read` 권한 토큰을 넣으면 zip 바로 다운로드. 현재 미설정 |
| 실행 비서 데이터 | `/root/data/assistant/` (H2 DB, `api_token`, `firebase.json`). 자세한 내용은 [assistant.md](assistant.md) |
| 실행 비서 자료·지도 키 | v3.3: 올린 자료 `/root/data/assistant/documents/`(매일 `backups/daily-docs-*.zip` 로 같이 백업), 카카오 REST 키 `/root/data/assistant/kakao_api_key`(600, 관리 화면 '지도 연결'이 저장) |
| 실행 비서 DB 백업 | `/root/data/assistant/backups/` — 스키마 변경 직전 `pre-V*.zip`(보관), 매일 04:00 `daily-*.zip`(7일). 앱이 직접 만든다(cron 아님). 복구: 앱 정지 → zip 안의 `assistant.mv.db` 를 `/root/data/assistant/` 에 덮어쓰기 → 앱 시작 |
| 실행 비서 푸시 | FCM. systemd drop-in `/etc/systemd/system/phonedrop.service.d/firebase.conf` 에 `ASSISTANT_PUSH_MODE=firebase`, `ASSISTANT_FIREBASE_CREDENTIALS=/root/data/assistant/firebase.json` |

## 서브도메인 페이지 구조

- `src/main/java/com/seobin/phonedrop/subdomain/SubdomainPageFilter.java`
  - `<name>.seobin.page/` 요청을 `static/<name>.html` 로 forward 한다.
  - `<name>`은 `[a-z0-9-]+` 이고 `www`는 제외. 해당 html이 없으면 기존 메인 화면이 그대로 나온다.
  - 루트 경로(`/`)만 처리한다. 서브도메인의 다른 경로(`/files` 등)는 메인 앱과 똑같이 동작한다.
- `static/<name>.html` 은 `https://seobin.page/<name>.html` 로도 열린다.

### 새 서브도메인 추가 방법

1. `src/main/resources/static/<name>.html` 을 만든다. master에 push하면 자동 배포된다.
2. 인증서에 `<name>.seobin.page`를 추가한다. 와일드카드 인증서가 아니므로 서브도메인마다 필요하다.
   `ops/run.sh`의 certbot `-d` 목록에 추가해서 `ops` 브랜치로 실행한다(아래 참고). **기존 도메인을 목록에서 빼면 인증서에서 빠진다.**
3. DNS는 와일드카드라 추가 작업 없음.

## 서버에서 명령 실행하기: `ops` 브랜치 워크플로

이 PC에서는 서버에 SSH로 직접 들어갈 수 없다(아래 "로컬 PC 제약"). 대신 GitHub Actions를 통해 실행한다.

- 파일 (모두 **`ops` 브랜치에만** 있음, master에는 없음)
  - `.github/workflows/server-ops.yml`: `ops` 브랜치에 push하면 실행. 기존 Secrets(`SERVER_SSH_KEY`, `SERVER_KNOWN_HOSTS`, `SERVER_HOST`, `SERVER_USER`, `SERVER_PORT`)로 서버에 root로 접속한다.
  - `ops/check.sh`: 사전 확인(certbot 존재, 인증서 lineage, nginx 설정). 실패하면 run.sh는 실행되지 않는다.
  - `ops/run.sh`: 실제로 실행할 명령. 작업할 때마다 이 파일을 바꿔서 push한다.
  - 워크플로는 실행할 때마다 Secret `FIREBASE_SERVICE_ACCOUNT` 를 서버 `/root/data/assistant/firebase.json` 에 다시 쓴다(값은 출력하지 않음).
- 사용법
  ```bash
  git switch ops
  # ops/run.sh 수정
  git commit -am "..." && git push origin ops
  git switch master
  ```
- 결과 확인 (`gh` 미설치, 공개 저장소라 인증 없이 조회 가능)
  ```bash
  curl -sS "https://api.github.com/repos/Seobin22/mobileapp/actions/runs?head_sha=<커밋 SHA>"
  curl -sS "https://api.github.com/repos/Seobin22/mobileapp/actions/runs/<run id>/jobs"
  ```
- 제약
  - 단계별 성공/실패만 볼 수 있다. **명령 출력(로그)은 로그인이 필요해 볼 수 없다**(API 403). 실패 원인은 사용자에게 Actions 화면 로그를 요청한다.
  - 대화형 명령 불가(입력 대기하는 명령은 멈춘다).
  - **run.sh 는 `ssh ... 'bash -s' < ops/run.sh` 로 표준입력에 실려 간다.** ffmpeg 처럼 표준입력을 읽는 명령이 나머지 스크립트를 먹어 버리므로, 그런 명령을 쓸 때는 전체를 `main() { ... }` 로 감싸고 `main < /dev/null` 로 실행한다(또는 `ffmpeg -nostdin`). 예: DNS TXT를 수동 입력하는 와일드카드 인증서 발급.
  - 공개 저장소라 커밋한 명령이 공개된다. 서버 출력이나 비밀값을 저장소에 커밋하지 않는다.
  - `ops`에 push 권한이 있으면 서버 root 명령 실행이 가능하다는 점을 유의.

### 실행 이력

| 날짜 | 내용 | 결과 |
|---|---|---|
| 2026-10-28 | `certbot certonly --nginx --expand --cert-name seobin.page -d seobin.page -d test.seobin.page` 후 nginx reload | 성공 (run 40637108002) |
| 2026-11-01 | `certbot certonly --nginx --expand --cert-name seobin.page -d seobin.page -d test.seobin.page -d check.seobin.page` 후 nginx reload | 성공 (run 41279402390) |
| 2026-11-02 | 위와 같은 명령에 `-d admin.seobin.page` 추가 | 성공 (run 41392538706) |
| 2026-11-13 | 위와 같은 명령에 `-d image.seobin.page` 추가 | 성공 (run 43387620186) |
| 2026-11-13 | 읽기 전용 조사: 도구 없음, nginx `client_max_body_size 500M`, 디스크 20G 여유 | 성공 |
| 2026-11-13 | `apt-get install --no-install-recommends ffmpeg libimage-exiftool-perl libheif-examples` | 성공 (run 43392826274) |
| 2026-11-13 | /tmp 에서 앱과 같은 ffmpeg 명령 시험: 회전 감지, 프레임 정확도(4곳 일치), HLG 톤매핑 캡처, 미리보기 변환(3초 영상 6.6초·약 200MB) | 성공 (run 43394955162) |
| 2026-11-13 | `libheif-plugin-libde265`, `libheif-plugin-x265` 설치 후 HEIC→JPG 시험 | 성공 (run 43395207945) |
| 2026-11-13 | /tmp 에서 슬라이드 동영상 명령 시험: 1440×1920 사진 3장×2초 → 6.03초 MP4, 인코딩 8.4초·약 260MB | 성공 (run 43401578394) |
| 2026-11-13 | /tmp 에서 원본 크기(3024×4032) 슬라이드 인코딩 메모리 시험: `-preset veryfast` 는 약 540MB 로 **OOM 강제 종료**, `ultrafast` 는 약 300MB·20초(H.264 Level 6.0). 슬라이드는 긴 변 1920 그대로 두기로 해서 적용 안 함. 원본 크기 인코딩을 넣으려면 ultrafast 필요 | 성공 (run 43409524396) |
| 2026-10-29 | Secret `FIREBASE_SERVICE_ACCOUNT` 를 `/root/data/assistant/firebase.json`(600)에 설치, firebase drop-in 추가 후 재시작(실패 시 자동 원복) | 성공 (run 40839120430) |

## 남은 일

- **인증서 갱신 후 nginx reload 훅 미설치.** `certonly`로 발급했기 때문에 자동 갱신 후 nginx가 예전 인증서를 계속 쓸 수 있다.
  만료(2027-01-31) 전에 설치해야 한다. Claude가 시도했으나 권한 검사에서 막혀 사용자 결정 대기 중. 서버에서 실행할 명령:
  ```bash
  printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh && chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
  certbot renew --cert-name seobin.page --dry-run
  ```

## 로컬 PC 제약 (Windows, 사용자 PC)

- JDK: Temurin 21 설치됨 (2026-10-28), `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\`, 시스템 `JAVA_HOME`·`PATH` 등록.
  Claude 앱을 재시작하기 전 세션에서는 환경변수가 안 보이므로 `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"` 후 사용.
- 로컬 검증 순서 (push 전):
  ```bash
  ./mvnw -B -q package -DskipTests
  # 임시 폴더(스크래치패드)에서 실행해 uploads/ data/ 가 프로젝트에 생기지 않게 한다. 127.0.0.1로 띄우면 방화벽 창이 안 뜬다.
  java -jar <프로젝트>/target/phonedrop-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
  curl http://127.0.0.1:8080/
  curl -H "Host: test.seobin.page" http://127.0.0.1:8080/   # 서브도메인 연결 확인
  ```
  확인이 끝나면 앱을 종료한다. HTTPS·nginx·인증서는 로컬에서 재현되지 않으므로 배포 후 운영 확인은 계속 한다.
- `gh` 없음 → CLAUDE.md의 `gh run ...` 대신 위의 GitHub API `curl`을 쓴다.
- 현재 네트워크가 **바깥으로 나가는 22번 포트를 차단**한다(GitHub 22번도 거부, 443·2222는 통과). 서버 sshd는 22번에서 정상 동작.
- 이 PC용 SSH 키 `~/.ssh/seobin_page`(ed25519)를 만들었지만, 서버 `authorized_keys` 등록 여부는 확인되지 않았다.
  직접 SSH가 필요하면: 공개키 등록 + 서버에 SSH 포트 2222 추가(Ubuntu 24.04는 `ssh.socket` 방식이라
  `/etc/systemd/system/ssh.socket.d/` override 필요) 또는 22번이 열린 네트워크 사용.
