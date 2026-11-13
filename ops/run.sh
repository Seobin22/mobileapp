#!/usr/bin/env bash
# HEIC 디코더 플러그인 확인·설치(libheif-plugin-libde265: heif-convert 읽기, libheif-plugin-x265: 시험용 HEIC 만들기) 후 /tmp 에서 HEIC 변환 시험.
# ssh 표준입력으로 전달되므로 전체를 함수로 감싸 </dev/null 로 실행한다.
main() {
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
W=/tmp/heiccheck; rm -rf "$W"; mkdir -p "$W"; cd "$W"
{
  echo "before: $(dpkg -l 'libheif-plugin-*' 2>/dev/null | awk '/^ii/{print $2}' | tr '\n' ' ')"
  apt-get install -y -q --no-install-recommends libheif-plugin-libde265 libheif-plugin-x265 >/dev/null 2>&1 && echo "install ok" || echo "install FAILED"
  echo "after: $(dpkg -l 'libheif-plugin-*' 2>/dev/null | awk '/^ii/{print $2}' | tr '\n' ' ')"
  ffmpeg -nostdin -hide_banner -v error -f lavfi -i testsrc2=size=1200x1600 -frames:v 1 src.png
  heif-enc -q 80 -o t.heic src.png 2>&1 | tail -2; ls -la t.heic 2>&1
  heif-convert -q 95 t.heic t.jpg 2>&1 | tail -2; ffprobe -v error -show_entries stream=width,height -of csv=p=0 t.jpg 2>&1 | sed 's/^/heic->jpg /'
  exiftool -j -n -ImageWidth -ImageHeight -BitDepthLuma -ColorSpaceData -ProfileDescription -Orientation t.heic 2>&1 | tr -d '\n '
  echo
} > out.txt 2>&1
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=img" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=img" n "::" buf }' out.txt
cd /; rm -rf "$W"
}
main < /dev/null
