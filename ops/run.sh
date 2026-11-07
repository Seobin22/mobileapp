#!/usr/bin/env bash
# 아침 루틴 놓침·적극 개입 말하기 조사(읽기 전용, 제목·내용 없음): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
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
SELECT 'NOW' AS q, FORMATDATETIME(CURRENT_TIMESTAMP,'MM-dd HH:mm','en','Asia/Seoul') AS now_kst FROM (VALUES 1);
SELECT 'W_DEF' AS q, id, kind, days, start_time, end_type, end_time, end_fallback_time, enabled, FORMATDATETIME(updated_at,'MM-dd HH:mm','en','Asia/Seoul') AS upd FROM time_windows;
SELECT 'W_RUNS' AS q, window_id, FORMATDATETIME(occ_start,'MM-dd HH:mm','en','Asia/Seoul') AS occ, alarm_count, FORMATDATETIME(alarm_last_at,'HH:mm','en','Asia/Seoul') AS alarm_last, FORMATDATETIME(acked_at,'HH:mm','en','Asia/Seoul') AS acked, llm_count, FORMATDATETIME(llm_last_at,'HH:mm','en','Asia/Seoul') AS llm_last, FORMATDATETIME(ended_at,'MM-dd HH:mm','en','Asia/Seoul') AS ended FROM time_window_runs ORDER BY id DESC LIMIT 6;
SELECT 'B' AS q, b.id, b.kind, b.status, b.task_id, b.goal_id, b.series_id, b.link_role, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(b.end_at,'HH:mm','en','Asia/Seoul') AS en, b.lead_minutes AS lead, FORMATDATETIME(b.created_at,'MM-dd HH:mm','en','Asia/Seoul') AS created, FORMATDATETIME(b.updated_at,'MM-dd HH:mm','en','Asia/Seoul') AS upd FROM blocks b WHERE b.start_at >= DATEADD('HOUR', -14, CURRENT_TIMESTAMP) AND b.start_at < DATEADD('HOUR', 8, CURRENT_TIMESTAMP) AND b.status <> 'cancelled' ORDER BY b.start_at;
SELECT 'L' AS q, l.block_id, l.type, l.actor, FORMATDATETIME(l.created_at,'MM-dd HH:mm','en','Asia/Seoul') AS at FROM block_logs l JOIN blocks b ON b.id=l.block_id WHERE b.start_at >= DATEADD('HOUR', -14, CURRENT_TIMESTAMP) AND b.start_at < DATEADD('HOUR', 8, CURRENT_TIMESTAMP) AND b.status <> 'cancelled' ORDER BY l.id;
SELECT 'F' AS q, block_id, status_final, outcome, outcome_src, card_state FROM block_facts WHERE logical_day >= DATEADD('DAY', -1, CURRENT_DATE) ORDER BY block_id;
SELECT 'I' AS q, i.id, i.block_id, i.status, i.level, i.reason, i.end_reason, FORMATDATETIME(i.started_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(i.ended_at,'HH:mm','en','Asia/Seoul') AS en FROM interventions i WHERE i.started_at >= DATEADD('HOUR', -14, CURRENT_TIMESTAMP) ORDER BY i.id;
SELECT 'T' AS q, trigger, status, COUNT(*) AS n, FORMATDATETIME(MIN(started_at),'MM-dd HH:mm','en','Asia/Seoul') AS first, FORMATDATETIME(MAX(started_at),'MM-dd HH:mm','en','Asia/Seoul') AS last FROM turns WHERE started_at >= DATEADD('HOUR', -14, CURRENT_TIMESTAMP) GROUP BY trigger, status ORDER BY first;
SELECT 'M' AS q, kind, silent, channel, COUNT(*) AS n, FORMATDATETIME(MAX(created_at),'MM-dd HH:mm','en','Asia/Seoul') AS last FROM messages WHERE role='assistant' AND created_at >= DATEADD('HOUR', -14, CURRENT_TIMESTAMP) GROUP BY kind, silent, channel;
SELECT 'D' AS q, id, app_version, FORMATDATETIME(last_heartbeat,'MM-dd HH:mm','en','Asia/Seoul') AS hb, capabilities FROM devices ORDER BY last_heartbeat DESC LIMIT 2;
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

# 3000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=lat" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=lat" n "::" buf }' "$WORK/out.txt"
