#!/usr/bin/env bash
# 이미지 관리 기능의 ffmpeg 명령 검증(읽기 전용, /tmp 에서만 작업): 앱과 같은 ffprobe·캡처·HDR 변환·미리보기 변환·HEIC 변환 명령을 시험 영상으로 실행한다.
set -uo pipefail
W=/tmp/imgcheck; rm -rf "$W"; mkdir -p "$W"; cd "$W"; trap 'rm -rf "$W"' EXIT
TM="zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv"
{
  # 1) 세로(회전 메타데이터) H.264 29.97fps 영상
  ffmpeg -hide_banner -v error -f lavfi -i testsrc2=size=1920x1080:rate=30000/1001:duration=4 -f lavfi -i sine=d=4 \
    -c:v libx264 -preset ultrafast -c:a aac -shortest -metadata:s:v:0 rotate=90 h264.mp4 && echo "made h264"
  ffmpeg -hide_banner -v error -display_rotation:v:0 90 -i h264.mp4 -c copy rot.mp4 2>/dev/null && mv rot.mp4 h264.mp4
  ffprobe -v error -print_format json -show_format -show_streams h264.mp4 | tr -d '\n ' | grep -o '"codec_name":"[^"]*"\|"avg_frame_rate":"[^"]*"\|"nb_frames":"[^"]*"\|"rotation":-\?[0-9]*\|"duration":"[^"]*"' | sort -u | tr '\n' ' '; echo
  # 2) 프레임 정확도: 앱과 같은 -ss (N/fps - 0.25/fps) 결과 = select 로 뽑은 N번째 프레임?
  for N in 0 1 37 100; do
    t=$(awk -v n=$N 'BEGIN{fps=30000/1001; s=n/fps-0.25/fps; if(s<0)s=0; printf "%.3f", s}')
    a=$(ffmpeg -hide_banner -v error -ss $t -i h264.mp4 -frames:v 1 -f rawvideo -pix_fmt rgb24 - | md5sum | cut -c1-8)
    b=$(ffmpeg -hide_banner -v error -i h264.mp4 -vf "select=eq(n\,$N)" -frames:v 1 -f rawvideo -pix_fmt rgb24 - | md5sum | cut -c1-8)
    echo "frame $N ss=$t $( [ "$a" = "$b" ] && echo MATCH || echo "DIFF $a $b")"
  done
  ffmpeg -hide_banner -v error -y -ss 1.000 -i h264.mp4 -frames:v 1 -q:v 2 cap.jpg && ffprobe -v error -show_entries stream=width,height -of csv=p=0 cap.jpg | sed 's/^/capture size /'
  # 3) HEVC 10bit HLG 영상: 톤매핑 캡처·썸네일·미리보기 변환
  ffmpeg -hide_banner -v error -f lavfi -i testsrc2=size=1280x720:rate=30:duration=3 -pix_fmt yuv420p10le -c:v libx265 \
     -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc hlg.mp4 2>/dev/null || \
  ffmpeg -hide_banner -v error -f lavfi -i testsrc2=size=1280x720:rate=30:duration=3 -c:v libx264 -pix_fmt yuv420p10le \
     -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc hlg.mp4
  ffprobe -v error -show_entries stream=codec_name,color_transfer,pix_fmt -of csv=p=0 hlg.mp4 | sed 's/^/hlg stream /'
  ffmpeg -hide_banner -v error -y -ss 1.000 -i hlg.mp4 -frames:v 1 -q:v 2 -vf "$TM" hdrcap.jpg && echo "tonemap capture OK $(stat -c %s hdrcap.jpg)" || echo "tonemap capture FAILED"
  ffmpeg -hide_banner -v error -y -ss 0.5 -i hlg.mp4 -frames:v 1 -q:v 2 -vf "$TM,scale=1600:1600:force_original_aspect_ratio=decrease" thumb.jpg && echo "thumb OK" || echo "thumb FAILED"
  /usr/bin/time -f "proxy %es %MKB" nice -n 10 ffmpeg -hide_banner -v error -y -i hlg.mp4 \
    -vf "$TM,scale='if(gt(iw,ih),-2,min(720,iw))':'if(gt(iw,ih),min(720,ih),-2)',format=yuv420p" \
    -c:v libx264 -preset veryfast -crf 26 -threads 1 -an -movflags +faststart proxy.mp4 && \
    ffprobe -v error -show_entries stream=codec_name,width,height,pix_fmt -of csv=p=0 proxy.mp4 | sed 's/^/proxy /'
  /usr/bin/time -f "proxy-rot %es %MKB" nice -n 10 ffmpeg -hide_banner -v error -y -i h264.mp4 \
    -vf "scale='if(gt(iw,ih),-2,min(720,iw))':'if(gt(iw,ih),min(720,ih),-2)',format=yuv420p" \
    -c:v libx264 -preset veryfast -crf 26 -threads 1 -an -movflags +faststart proxy2.mp4 && \
    ffprobe -v error -show_entries stream=width,height -of csv=p=0 proxy2.mp4 | sed 's/^/proxy-rot size /'
  # 4) HEIC: heif-enc 로 만들고 heif-convert·exiftool 로 읽기
  ffmpeg -hide_banner -v error -f lavfi -i testsrc2=size=1200x1600 -frames:v 1 src.png
  heif-enc -q 80 -o t.heic src.png >/dev/null 2>&1 && echo "heic made $(stat -c %s t.heic)"
  heif-convert -q 95 t.heic t.jpg >/dev/null 2>&1 && ffprobe -v error -show_entries stream=width,height -of csv=p=0 t.jpg | sed 's/^/heic->jpg /'
  exiftool -j -n -ImageWidth -ImageHeight -BitDepthLuma -ColorSpaceData -ProfileDescription t.heic | tr -d '\n '
  echo; free -m | sed -n 2p
} > out.txt 2>&1
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=img" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=img" n "::" buf }' out.txt
