#!/usr/bin/env bash
# 음성 탭 상태 조회(읽기 전용): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
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
SELECT 'V_SESSIONS' AS q, id, FORMATDATETIME(started_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS started, FORMATDATETIME(ended_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS ended, end_reason, stt_seconds, tts_chars, llm_tokens FROM voice_sessions ORDER BY id DESC LIMIT 15;
SELECT 'V_TURNS' AS q, status, COUNT(*) AS n FROM turns WHERE trigger='voice' GROUP BY status;
SELECT 'V_MSGS' AS q, role, kind, COUNT(*) AS n FROM messages WHERE channel='voice' GROUP BY role, kind;
SELECT 'V_SET' AS q, key, value FROM settings WHERE key IN ('voice.stt_model','voice.stt_mode','voice.model','voice.speed','voice.end_silence_ms','voice.daily_turn_cap') OR (key='voice.voice_id');
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 키 파일 유무(값은 출력하지 않음)와 ElevenLabs 응답 코드만
{ [ -s "$DATA/elevenlabs_api_key" ] && echo "KEY present $(wc -c < "$DATA/elevenlabs_api_key") bytes" || echo "KEY missing"; } >> "$WORK/out.txt"
journalctl -u phonedrop --since "-6h" --no-pager 2>/dev/null | grep -oE "ElevenLabs [0-9]{3}|STT model [a-z0-9_]+ rejected|voice turn [0-9]+ did not finish" | sort | uniq -c >> "$WORK/out.txt" || true

# 3000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=lat" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=lat" n "::" buf }' "$WORK/out.txt"
