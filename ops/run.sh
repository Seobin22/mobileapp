#!/usr/bin/env bash
# 실행 비서 API 비용 조사(읽기 전용): DB 파일 복사본에서 turn 별 토큰 집계만 읽는다.
# 운영 DB·서비스는 건드리지 않는다. 결과는 공개 API 로 읽을 수 있도록 ::notice 주석으로 출력한다(메시지 내용은 출력하지 않음).
set -euo pipefail

WORK=/tmp/assistant-cost-check
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
SELECT 'DAY' AS q, FORMATDATETIME(started_at, 'MM-dd', 'en', 'Asia/Seoul') AS kst_day, COUNT(*) AS turns, SUM(CASE WHEN status IN ('ok','partial') THEN 1 ELSE 0 END) AS ran, SUM(tool_calls) AS tools, SUM(COALESCE(input_tokens,0)) AS input, SUM(COALESCE(output_tokens,0)) AS output, SUM(cache_read_tokens) AS c_read, SUM(cache_write_tokens) AS c_write, ROUND((SUM(COALESCE(input_tokens,0))*2.0 + SUM(COALESCE(output_tokens,0))*10.0 + SUM(cache_read_tokens)*0.2 + SUM(cache_write_tokens)*2.5)/1000000, 3) AS usd FROM turns GROUP BY kst_day ORDER BY kst_day;
SELECT 'TRIGGER' AS q, trigger, COUNT(*) AS turns, SUM(CASE WHEN status IN ('ok','partial') THEN 1 ELSE 0 END) AS ran, ROUND(AVG(tool_calls),1) AS avg_tools, MAX(tool_calls) AS max_tools, SUM(COALESCE(input_tokens,0)) AS input, SUM(COALESCE(output_tokens,0)) AS output, SUM(cache_read_tokens) AS c_read, SUM(cache_write_tokens) AS c_write, ROUND((SUM(COALESCE(input_tokens,0))*2.0 + SUM(COALESCE(output_tokens,0))*10.0 + SUM(cache_read_tokens)*0.2 + SUM(cache_write_tokens)*2.5)/1000000, 3) AS usd FROM turns WHERE started_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) GROUP BY trigger ORDER BY usd DESC;
SELECT 'STATUS' AS q, status, COUNT(*) AS n FROM turns WHERE started_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) GROUP BY status;
SELECT 'TURN' AS q, id, FORMATDATETIME(started_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS kst, trigger, status, tool_calls AS tc, COALESCE(input_tokens,0) AS inp, COALESCE(output_tokens,0) AS outp, cache_read_tokens AS cr, cache_write_tokens AS cw, ROUND((COALESCE(input_tokens,0)*2.0 + COALESCE(output_tokens,0)*10.0 + cache_read_tokens*0.2 + cache_write_tokens*2.5)/1000000, 3) AS usd, LEFT(REGEXP_REPLACE(trigger_ref, '"(reason|kind)":"[^"]*"', '"$1":x'), 50) AS ref, LEFT(error, 40) AS err FROM turns WHERE started_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) ORDER BY id DESC LIMIT 120;
SELECT 'BLOCKS' AS q, kind, status, COUNT(*) AS n FROM blocks WHERE created_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) GROUP BY kind, status ORDER BY kind, status;
SELECT 'GOALS' AS q, id, status, waiting_until IS NOT NULL AS waiting FROM goals ORDER BY id;
SELECT 'MSG' AS q, FORMATDATETIME(created_at, 'MM-dd', 'en', 'Asia/Seoul') AS kst_day, role, kind, COUNT(*) AS n FROM messages WHERE created_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) GROUP BY kst_day, role, kind ORDER BY kst_day, role, kind;
SELECT 'JOBS' AS q, type, status, COUNT(*) AS n FROM jobs WHERE updated_at >= DATEADD('DAY', -3, CURRENT_TIMESTAMP) GROUP BY type, status ORDER BY type, status;
SELECT 'SETTINGS' AS q, key, LEFT(value, 60) AS v FROM settings WHERE key IN ('web.enabled','pulse.interval_minutes','agent.max_tool_calls','agent.daily_turn_soft','agent.daily_turn_hard','life.wake','life.sleep','agent.context_messages');
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 로그에서 비용과 관련된 경고 횟수만 센다(내용은 출력하지 않음).
{
  echo "LOG coverage_exhausted $(journalctl -u phonedrop --since '3 days ago' --no-pager | grep -c 'coverage retries exhausted' || true)"
  echo "LOG llm_failure $(journalctl -u phonedrop --since '3 days ago' --no-pager | grep -c 'LLM failure' || true)"
  echo "LOG app_model $(journalctl -u phonedrop --since '3 days ago' --no-pager | grep -o 'claude-[a-z0-9-]*' | sort | uniq -c | tr '\n' ' ' || true)"
} >> "$WORK/out.txt"

# 6000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=6000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=cost" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=cost" n "::" buf }' "$WORK/out.txt"
