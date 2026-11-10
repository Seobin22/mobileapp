#!/usr/bin/env bash
# 적극 개입 시간대 소리 조사(읽기 전용, 제목·내용 없음): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
# 운영 DB·서비스는 건드리지 않는다. 결과는 공개 API 로 읽을 수 있도록 ::notice 주석으로 출력한다(메시지 내용은 출력하지 않음).
set -euo pipefail

WORK=/tmp/assistant-latency-check
DATA=/root/data/assistant
APP_JAR=/root/phonedrop-0.0.1-SNAPSHOT.jar
rm -rf "$WORK"
mkdir -p "$WORK/live" "$WORK/backup"
trap 'rm -rf "$WORK"' EXIT

JAVA=$(command -v java || true)
if [ -z "$JAVA" ]; then
  JAVA=$(systemctl show -p ExecStart phonedrop | grep -o 'path=[^ ;]*' | head -1 | cut -d= -f2)
fi

# 앱 jar 안의 H2 jar 를 꺼낸다.
if command -v unzip > /dev/null; then
  unzip -q -o -j "$APP_JAR" 'BOOT-INF/lib/h2-*.jar' -d "$WORK"
else
  python3 - "$APP_JAR" "$WORK" <<'PY'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.startswith('BOOT-INF/lib/h2-') and n.endswith('.jar'):
        open(os.path.join(sys.argv[2], os.path.basename(n)), 'wb').write(z.read(n))
PY
fi
H2=$(ls "$WORK"/h2-*.jar | head -1)

cp -p "$DATA/assistant.mv.db" "$WORK/live/assistant.mv.db"
URL="jdbc:h2:$WORK/live/assistant;NON_KEYWORDS=KEY,VALUE"
if ! "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "SELECT COUNT(*) FROM turns" > /dev/null 2>&1; then
  latest=$(ls -t "$DATA"/backups/daily-*.zip | head -1)
  "$JAVA" -cp "$H2" org.h2.tools.Restore -file "$latest" -dir "$WORK/backup" -quiet
  URL="jdbc:h2:$WORK/backup/assistant;NON_KEYWORDS=KEY,VALUE"
  echo "SOURCE backup $(basename "$latest")" > "$WORK/out.txt"
else
  echo "SOURCE live copy $(date '+%m-%d %H:%M %Z')" > "$WORK/out.txt"
fi

cat > "$WORK/queries.sql" <<'SQL'
SELECT 'NOW' AS q, FORMATDATETIME(CURRENT_TIMESTAMP,'MM-dd HH:mm','en','Asia/Seoul') AS now_kst, (SELECT value FROM settings WHERE key='active.paused_until') AS paused FROM (VALUES 1);
SELECT 'D' AS q, id, app_version, FORMATDATETIME(last_heartbeat,'MM-dd HH:mm','en','Asia/Seoul') AS hb FROM devices ORDER BY last_heartbeat DESC LIMIT 1;
SELECT 'W_RUNS' AS q, window_id, FORMATDATETIME(occ_start,'MM-dd HH:mm','en','Asia/Seoul') AS occ, alarm_count, FORMATDATETIME(acked_at,'HH:mm','en','Asia/Seoul') AS acked, llm_count, FORMATDATETIME(ended_at,'MM-dd HH:mm','en','Asia/Seoul') AS ended FROM time_window_runs ORDER BY id DESC LIMIT 2;
SELECT 'M' AS q, m.id, m.kind, m.silent, FORMATDATETIME(m.created_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS at FROM messages m WHERE m.role='assistant' AND m.created_at >= DATEADD('MINUTE', -60, CURRENT_TIMESTAMP) ORDER BY m.id;
SELECT 'B' AS q, b.id, b.kind, b.status, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(b.end_at,'HH:mm','en','Asia/Seoul') AS en, b.lead_minutes AS lead FROM blocks b WHERE b.status <> 'cancelled' AND b.end_at >= DATEADD('MINUTE', -60, CURRENT_TIMESTAMP) AND b.start_at < DATEADD('HOUR', 2, CURRENT_TIMESTAMP) ORDER BY b.start_at;
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 앱이 비서 목소리를 받으러 왔는지(경로·시각·응답 코드만)와 ElevenLabs 응답 코드
{ echo "AUDIO_REQ (last 4h, KST hour:min status)"; for f in /var/log/nginx/access.log /var/log/nginx/access.log.1; do [ -f "$f" ] && grep -h "voice/messages/[0-9]*/audio" "$f"; done | awk '{print $4, $9}' | tail -40; } >> "$WORK/out.txt" 2>/dev/null || true
{ echo "VOICE_TURN_REQ"; for f in /var/log/nginx/access.log; do [ -f "$f" ] && grep -h "voice/sessions/[0-9]*/turn" "$f"; done | awk '{print $4, $9}' | tail -10; } >> "$WORK/out.txt" 2>/dev/null || true
{ echo "ELEVEN"; journalctl -u phonedrop --since "-8h" --no-pager 2>/dev/null | grep -oE "ElevenLabs [0-9]{3}|ElevenLabs 연결 실패" | sort | uniq -c; } >> "$WORK/out.txt" || true

# 3000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=lat" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=lat" n "::" buf }' "$WORK/out.txt"
