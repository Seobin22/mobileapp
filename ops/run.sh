#!/usr/bin/env bash
# 원본 크기 슬라이드 동영상 인코딩 메모리 시험(읽기 전용, /tmp 에서만): 3024x4032 사진 3장을 앱과 같은 명령(+메모리 줄이는 옵션)으로 인코딩.
# ssh 표준입력으로 전달되므로 전체를 함수로 감싸 </dev/null 로 실행한다.
main() {
set -uo pipefail
W=/tmp/bigslide; rm -rf "$W"; mkdir -p "$W"; cd "$W"
{
  free -m | sed -n 2p
  for i in 1 2 3; do ffmpeg -nostdin -hide_banner -v error -f lavfi -i testsrc2=size=3024x4032 -frames:v 1 -q:v 2 f000$i.jpg; done
  printf "file 'f0001.jpg'\nduration 2.000\nfile 'f0002.jpg'\nduration 2.000\nfile 'f0003.jpg'\nduration 2.000\nfile 'f0003.jpg'\n" > list.txt
  for opts in "veryfast" "veryfast -rc-lookahead 5 -bf 0" "ultrafast"; do
    set -- $opts
    /usr/bin/time -f "[$opts] %es %MKB" nice -n 10 ffmpeg -nostdin -hide_banner -v error -y -f concat -safe 0 -i "$W/list.txt" \
      -vf fps=30,format=yuv420p -c:v libx264 -preset "$@" -crf 20 -tune stillimage -threads 1 -movflags +faststart -an out.mp4 \
      && echo "  ok $(stat -c %s out.mp4) bytes $(ffprobe -v error -show_entries stream=profile,level,width,height -of csv=p=0 out.mp4)"
  done
} > out.txt 2>&1
awk '{ line=$0; gsub(/%/, "%25", line); buf = buf line "%0A" } END { print "::notice title=big::" buf }' out.txt
cd /; rm -rf "$W"
}
main < /dev/null
