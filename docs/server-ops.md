# 서버·도메인 운영 노트

다른 채팅(세션)에서 이어서 작업할 수 있도록, 서버·도메인·인증서 관련해 지금까지 확인하고 바꾼 내용을 정리한다.
마지막 갱신: 2026-10-28

## 현재 상태 요약

| 항목 | 상태 |
|---|---|
| 서버 | DigitalOcean droplet `phonedrop`, IP `139.59.99.165`, Ubuntu 24.04, nginx 1.24 → Spring 앱(8080) |
| DNS | `seobin.page`, 와일드카드 `*.seobin.page` 모두 서버 IP로 연결됨 (사용자가 등록) |
| 인증서 | Let's Encrypt, lineage 이름 `seobin.page`, 포함 도메인 `seobin.page`, `test.seobin.page`, 만료 2027-01-26 |
| nginx | HTTPS(443)는 모든 Host를 앱으로 넘긴다. HTTP(80)로 `test.seobin.page`에 접속하면 nginx 404 (HTTPS로만 사용) |
| 서브도메인 | `test.seobin.page` → 현재 시간 페이지 (운영 중) |

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
  - 대화형 명령 불가(입력 대기하는 명령은 멈춘다). 예: DNS TXT를 수동 입력하는 와일드카드 인증서 발급.
  - 공개 저장소라 커밋한 명령이 공개된다. 서버 출력이나 비밀값을 저장소에 커밋하지 않는다.
  - `ops`에 push 권한이 있으면 서버 root 명령 실행이 가능하다는 점을 유의.

### 실행 이력

| 날짜 | 내용 | 결과 |
|---|---|---|
| 2026-10-28 | `certbot certonly --nginx --expand --cert-name seobin.page -d seobin.page -d test.seobin.page` 후 nginx reload | 성공 (run 40637108002) |

## 남은 일

- **인증서 갱신 후 nginx reload 훅 미설치.** `certonly`로 발급했기 때문에 자동 갱신 후 nginx가 예전 인증서를 계속 쓸 수 있다.
  만료(2027-01-26) 전에 설치해야 한다. Claude가 시도했으나 권한 검사에서 막혀 사용자 결정 대기 중. 서버에서 실행할 명령:
  ```bash
  printf '#!/bin/sh\nsystemctl reload nginx\n' > /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh && chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
  certbot renew --cert-name seobin.page --dry-run
  ```

## 로컬 PC 제약 (Windows, 사용자 PC)

- JDK 없음 → `./mvnw` 로컬 빌드 불가. CI 빌드로 검증한다.
- `gh` 없음 → CLAUDE.md의 `gh run ...` 대신 위의 GitHub API `curl`을 쓴다.
- 현재 네트워크가 **바깥으로 나가는 22번 포트를 차단**한다(GitHub 22번도 거부, 443·2222는 통과). 서버 sshd는 22번에서 정상 동작.
- 이 PC용 SSH 키 `~/.ssh/seobin_page`(ed25519)를 만들었지만, 서버 `authorized_keys` 등록 여부는 확인되지 않았다.
  직접 SSH가 필요하면: 공개키 등록 + 서버에 SSH 포트 2222 추가(Ubuntu 24.04는 `ssh.socket` 방식이라
  `/etc/systemd/system/ssh.socket.d/` override 필요) 또는 22번이 열린 네트워크 사용.
