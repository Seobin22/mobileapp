#!/usr/bin/env bash
# 이미지 관리 기능 사전 조사(읽기 전용): 도구 설치 여부, nginx 업로드 한도, 디스크·메모리·CPU.
set -uo pipefail
out=$(mktemp)
{
  for t in ffmpeg ffprobe exiftool heif-convert; do printf '%s=%s\n' "$t" "$(command -v $t || echo none)"; done
  ffmpeg -version 2>/dev/null | head -1
  apt-cache policy ffmpeg libimage-exiftool-perl libheif-examples 2>/dev/null | grep -E '^[a-z]|Candidate|Installed'
  echo "NGINX_BODY:"; grep -rhn client_max_body_size /etc/nginx/ 2>/dev/null
  echo "DISK:"; df -h / | tail -1
  echo "MEM:"; free -m | sed -n 2p
  echo "CPU: $(nproc)"
  echo "JAVA: $(systemctl show -p ExecStart phonedrop | grep -o 'Xmx[0-9a-z]*')"
} > "$out" 2>&1
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=img" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=img" n "::" buf }' "$out"
