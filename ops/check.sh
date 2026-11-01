#!/usr/bin/env bash
# 작업 전 확인: 실패하면 run.sh는 실행되지 않는다.
set -euo pipefail

command -v certbot
test -d /etc/letsencrypt/live/seobin.page
grep -rqs "/etc/letsencrypt/live/seobin.page/" /etc/nginx/
nginx -t
