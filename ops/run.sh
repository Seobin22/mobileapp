#!/usr/bin/env bash
# 실행 비서 푸시 알림 조사(읽기 전용): DB 파일 복사본에서 기기·메시지 발송 상태만 읽는다.
# 운영 DB·서비스는 건드리지 않는다. 결과는 공개 API 로 읽을 수 있도록 ::notice 주석으로 출력한다(메시지 내용은 출력하지 않음).
set -euo pipefail

WORK=/tmp/assistant-push-check
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
true
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
SELECT 'DEV' AS q, id, LEFT(fcm_token, 6) AS tok, LENGTH(fcm_token) AS len, app_version, FORMATDATETIME(last_heartbeat, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS hb, FORMATDATETIME(created_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS created, capabilities FROM devices ORDER BY id;
SELECT 'MSG' AS q, id, FORMATDATETIME(created_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS kst, role, kind, importance, silent, read_at IS NOT NULL AS rd FROM messages WHERE role <> 'user' AND created_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP) ORDER BY id DESC LIMIT 40;
SQL
while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 실행 중 앱의 푸시 모드와 최근 발송 기록(메모리). 토큰은 출력하지 않는다.
TOKEN=$(cat "$DATA/api_token" 2>/dev/null || true)
curl -s -m 10 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/assistant/v1/admin/status > "$WORK/status.json" || true
command -v python3 > /dev/null || echo "NOPY $(head -c 600 "$WORK/status.json")" >> "$WORK/out.txt"
python3 - "$WORK/status.json" >> "$WORK/out.txt" <<'PY' || true
import json, sys
try:
    s = json.load(open(sys.argv[1]))
except Exception as e:
    print("STATUS unreadable", e); sys.exit()
print("PUSHMODE", s.get("push_mode"), "now", s.get("now"), "quiet", s.get("quiet_now"))
for p in s.get("recent_pushes", [])[:30]:
    print("PUSH", p.get("at", "")[5:16], p.get("delivered"), p.get("type"), "silent=" + str(p.get("silent")), "ch=" + str(p.get("channel")))
PY
true

{
  echo "DROPIN $(cat /etc/systemd/system/phonedrop.service.d/firebase.conf 2>/dev/null | grep -o 'ASSISTANT_PUSH_MODE=[a-z]*' || echo none)"
  echo "CRED $(test -s $DATA/firebase.json && echo present || echo missing)"
  echo "LOG push_failed $(journalctl -u phonedrop --since '2 days ago' --no-pager | grep -c 'push to device' || true)"
  echo "LOG token_invalid $(journalctl -u phonedrop --since '2 days ago' --no-pager | grep -c 'token invalid' || true)"
  journalctl -u phonedrop --since '2 days ago' --no-pager | grep -A2 'push to device' | grep -o 'FCM[^{]*\|status[^,]*\|"message": "[^"]*"\|IllegalStateException: [^:]*' | sort | uniq -c | head -10 | sed 's/^/LOGERR /' || true
} >> "$WORK/out.txt"

awk -v max=3500 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=push" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=push" n "::" buf }' "$WORK/out.txt"
