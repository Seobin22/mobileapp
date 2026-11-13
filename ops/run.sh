#!/usr/bin/env bash
# 이미지 관리 기능(image.seobin.page)에 필요한 도구 설치: ffmpeg/ffprobe(영상 분석·장면 캡처), exiftool(HEIC 메타데이터), heif-convert(HEIC 변환).
# 앱·nginx·데이터는 건드리지 않는다.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends ffmpeg libimage-exiftool-perl libheif-examples
out=$(mktemp)
{
  for t in ffmpeg ffprobe exiftool heif-convert; do printf '%s=%s\n' "$t" "$(command -v $t || echo none)"; done
  ffmpeg -version | head -1
  echo "zscale=$(ffmpeg -hide_banner -filters 2>/dev/null | grep -c ' zscale ')"
  echo "tonemap=$(ffmpeg -hide_banner -filters 2>/dev/null | grep -c ' tonemap ')"
  echo "libx264=$(ffmpeg -hide_banner -encoders 2>/dev/null | grep -c libx264)"
  echo "hevcdec=$(ffmpeg -hide_banner -decoders 2>/dev/null | grep -c ' hevc ')"
  exiftool -ver
  heif-convert --version 2>&1 | head -1 || true
  df -h / | tail -1
  swapon --show | tail -n +2 | head -2; echo "swap_end"
} > "$out" 2>&1
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=img" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=img" n "::" buf }' "$out"
