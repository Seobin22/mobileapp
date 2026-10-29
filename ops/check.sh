#!/usr/bin/env bash
# 작업 전 확인: 실패하면 run.sh는 실행되지 않는다.
set -euo pipefail

systemctl cat phonedrop > /dev/null
test -d /root/data/assistant
# 워크플로가 Secrets 에서 넣은 Firebase 서비스 계정 키(내용은 출력하지 않는다)
test -s /root/data/assistant/firebase.json
test "$(stat -c %a /root/data/assistant/firebase.json)" = 600
grep -q '"type": *"service_account"' /root/data/assistant/firebase.json
