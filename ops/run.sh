#!/usr/bin/env bash
# 실행 비서 응답 지연·무응답 조사(읽기 전용): DB 파일 복사본에서 turn·메시지 시각과 토큰만 읽는다.
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
SELECT 'SETTINGS' AS q, key, LEFT(value, 60) AS v FROM settings WHERE key IN ('llm.provider','llm.model','web.enabled','agent.max_tool_calls','goal.coverage_retry','agent.context_messages');
SELECT 'STATUS' AS q, status, COUNT(*) AS n FROM turns WHERE started_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP) GROUP BY status;
SELECT 'LAT' AS q, m.id, FORMATDATETIME(m.created_at, 'MM-dd HH:mm:ss', 'en', 'Asia/Seoul') AS kst, m.intervention_id IS NOT NULL AS fs, t.id AS tid, t.status, t.tool_calls AS tc, DATEDIFF('SECOND', m.created_at, t.started_at) AS wait_s, DATEDIFF('SECOND', t.started_at, t.ended_at) AS run_s, (SELECT DATEDIFF('SECOND', m.created_at, MIN(a.created_at)) FROM messages a WHERE a.role = 'assistant' AND a.id > m.id) AS first_reply_s, t.input_tokens AS inp, t.output_tokens AS outp, t.cache_read_tokens AS cr, t.cache_write_tokens AS cw, LEFT(t.error, 120) AS err FROM messages m LEFT JOIN turns t ON t.trigger IN ('user_message','intervention_talk') AND t.trigger_ref LIKE CONCAT('%"message_id":', m.id, '%') WHERE m.role = 'user' AND m.created_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) ORDER BY m.id DESC LIMIT 60;
SELECT 'TURN' AS q, id, FORMATDATETIME(started_at, 'MM-dd HH:mm:ss', 'en', 'Asia/Seoul') AS kst, trigger, status, tool_calls AS tc, DATEDIFF('SECOND', started_at, ended_at) AS run_s, COALESCE(input_tokens,0) AS inp, COALESCE(output_tokens,0) AS outp, cache_read_tokens AS cr, cache_write_tokens AS cw, LEFT(error, 120) AS err FROM turns WHERE started_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP) ORDER BY id DESC LIMIT 80;
SELECT 'TRIG' AS q, trigger, COUNT(*) AS n, ROUND(AVG(DATEDIFF('SECOND', started_at, ended_at)),1) AS avg_run_s, MAX(DATEDIFF('SECOND', started_at, ended_at)) AS max_run_s, ROUND(AVG(tool_calls),1) AS avg_tc FROM turns WHERE started_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) AND ended_at IS NOT NULL GROUP BY trigger ORDER BY n DESC;
SELECT 'QUEUE' AS q, status, COUNT(*) AS n, MIN(FORMATDATETIME(fire_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul')) AS oldest FROM jobs WHERE type = 'turn' AND status IN ('scheduled','running') GROUP BY status;
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 로그에서 응답 지연·실패와 관련된 줄 수와 최근 실패 요약만(내용·키는 출력하지 않음).
J=$(journalctl -u phonedrop --since '2 days ago' --no-pager)
{
  echo "LOG llm_failure $(echo "$J" | grep -c 'LLM failure' || true)"
  echo "LOG turn_skipped $(echo "$J" | grep -c 'skipped:' || true)"
  echo "LOG turn_job_failed $(echo "$J" | grep -c 'turn job .* failed' || true)"
  echo "LOG worker_loop_failed $(echo "$J" | grep -c 'agent worker loop failed' || true)"
  echo "LOG tool_failed $(echo "$J" | grep -c 'tool .* failed in turn' || true)"
  echo "LOG restarts $(echo "$J" | grep -c 'Started PhonedropApplication' || true)"
  echo "$J" | grep -E 'LLM failure|skipped:|turn job .* failed' | tail -8 | sed -E 's/(sk-|key=)[A-Za-z0-9_-]+/***/g' | cut -c1-220 | sed 's/^/LOGLINE /' || true
} >> "$WORK/out.txt"

# 6000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=6000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=lat" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=lat" n "::" buf }' "$WORK/out.txt"
