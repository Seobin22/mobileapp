# PhoneDrop — Claude 작업 규칙

이 레포는 https://seobin.page/ 로 서비스되는 운영 중인 웹앱이다.
**master에 push하는 순간 프로덕션에 자동 배포된다.** 아래 규칙을 지켜서, 사용자가 한 번 요청하면
설계 → 구현 → push → 배포 확인 → 보고까지 스스로 끝낸다.

## 스택
- Spring Boot 4.1.0, Java 21, Maven wrapper(`./mvnw`), 단일 jar `phonedrop-0.0.1-SNAPSHOT.jar`
- 패키지: `com.seobin.phonedrop` (`src/main/java/com/seobin/phonedrop/`)
- 프론트: `src/main/resources/static/` 의 정적 HTML/JS (빌드 도구 없음, 순수 HTML+JS)
- 설정: `src/main/resources/application.properties` (비밀값 넣지 말 것)
- 포트 8080, 서버 JVM `-Xmx512m` → 무거운 의존성·대용량 메모리 처리 금지

## 배포 구조 (수정 금지 영역)
- `.github/workflows/deploy.yml`: master push → 빌드 → 서버 jar 교체(.bak 백업) → `systemctl restart phonedrop` → 상태·HTTP 확인
- 빌드가 실패하면 서버는 바뀌지 않는다. 배포 단계가 실패하면 워크플로가 빨간색이 된다.
- 다음은 사용자 승인 없이 바꾸지 않는다: `deploy.yml`, `pom.xml`의 `artifactId`/`version`(jar 이름이 바뀌면 배포가 깨짐), GitHub Secrets.
- 서버: DigitalOcean, root, WorkingDirectory `/root`. 서버 명령은 `ops` 브랜치 워크플로로 실행할 수 있다.
- 서버·도메인·인증서·서브도메인 현황과 서버 명령 실행 방법은 **`docs/server-ops.md`를 먼저 읽는다.** 서버나 도메인 관련 작업을 하면 그 문서도 갱신한다.

## 새 기능("○○ 프로그램을 서버에 올려")을 만들 때
1. **기존 앱 안에 추가**한다. 새 포트·새 서비스·nginx 변경이 필요한 설계는 하지 말고, 필요하면 사용자에게 먼저 보고한다.
2. 기능마다 경로를 하나 정한다(영문 소문자, 예: `organize`):
   - 화면: `src/main/resources/static/<feature>.html` → https://seobin.page/<feature>.html
   - API: `src/main/java/com/seobin/phonedrop/<feature>/` 아래 Controller/Service, URL 접두사 `/api/<feature>/`
   - 서버 데이터: 상대경로 `data/<feature>/` (실제 위치 `/root/data/<feature>/`)
3. `index.html`의 기존 기능은 유지하고, 메인 화면에 새 페이지로 가는 링크 한 줄만 추가한다.
4. 의존성은 가능한 한 추가하지 않는다. 꼭 필요하면 이유를 보고에 적는다.

## 절대 건드리지 말 것
- 서버의 `uploads/` 폴더(사용자 업로드 파일). 새 기능이 이 폴더를 읽는 것은 괜찮지만, 삭제·이동·덮어쓰기는
  사용자가 명시적으로 요청한 경우에만 한다. "정리" 기능은 원본을 지우지 말고 복사하거나 `data/<feature>/`에 결과를 만든다.
- 기존 API 경로(`/upload`, `/files`, 텍스트 전송 등)의 동작 변경 — 요청받은 경우만.

## 작업 순서 (매번)
1. 요구사항을 3~5줄 설계로 정리한다(경로, 파일, API). 모호해도 합리적으로 정하고 진행하며, 가정은 보고에 적는다.
2. 구현한다. 가능하면 `./mvnw -B -q package -DskipTests`로 컴파일을 확인한다(네트워크가 막혀 있으면 생략 가능 — CI가 빌드를 검증한다).
3. master에 직접 commit하고 push한다. 커밋 메시지는 무엇을 했는지 한 줄 영어로.
4. GitHub Actions 실행 결과를 확인한다(`gh run list --branch master -L 1`, `gh run watch`, 실패 시 `gh run view --log-failed`). 사용자 PC에는 `gh`가 없으므로 `docs/server-ops.md`의 GitHub API `curl` 방법을 쓴다.
5. 성공하면 https://seobin.page/ 와 새 페이지가 응답하는지 확인한다.
6. 실패하면 원인을 고쳐 다시 push한다. 두 번 고쳐도 실패하면 멈추고 보고한다.
7. 보고: 바뀐 것, 확인할 URL, 사용법, 가정한 점.

## 롤백 (사용자가 서버 웹 콘솔에서 실행)
```bash
cd /root
cp -p phonedrop-0.0.1-SNAPSHOT.jar phonedrop-0.0.1-SNAPSHOT.jar.broken
cp -p phonedrop-0.0.1-SNAPSHOT.jar.bak phonedrop-0.0.1-SNAPSHOT.jar
systemctl restart phonedrop
```
코드로 되돌릴 때는 `git revert <커밋>` 후 push (다시 자동 배포됨).
