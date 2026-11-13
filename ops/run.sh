#!/usr/bin/env bash
# 슬라이드 동영상 명령 검증(읽기 전용, /tmp 에서만): 앱과 같은 concat + libx264 명령으로 사진 3장 → MP4, 길이·크기 확인.
# ssh 표준입력으로 전달되므로 전체를 함수로 감싸 </dev/null 로 실행한다.
main() {
set -uo pipefail
W=/tmp/slidecheck; rm -rf "$W"; mkdir -p "$W"; cd "$W"
{
  for i in 1 2 3; do ffmpeg -nostdin -hide_banner -v error -f lavfi -i testsrc2=size=1440x1920 -frames:v 1 -q:v 2 f000$i.jpg; done
  printf "file 'f0001.jpg'\nduration 2.000\nfile 'f0002.jpg'\nduration 2.000\nfile 'f0003.jpg'\nduration 2.000\nfile 'f0003.jpg'\n" > list.txt
  /usr/bin/time -f "encode %es %MKB" nice -n 10 ffmpeg -nostdin -hide_banner -v error -y -f concat -safe 0 -i "$W/list.txt" \
    -vf fps=30,format=yuv420p -c:v libx264 -preset veryfast -crf 20 -tune stillimage -threads 1 -movflags +faststart -an out.mp4 \
    && echo "ok $(stat -c %s out.mp4) bytes"
  ffprobe -v error -show_entries format=duration:stream=codec_name,width,height,pix_fmt,nb_frames -of compact out.mp4
} > out.txt 2>&1
awk '{ line=$0; gsub(/%/, "%25", line); buf = buf line "%0A" } END { print "::notice title=slide::" buf }' out.txt
cd /; rm -rf "$W"
}
main < /dev/null
