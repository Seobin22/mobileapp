#!/usr/bin/env bash
# 완료 처리 후 계속 오는 알림 조사(읽기 전용, 제목·내용 없음): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
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
SELECT 'NOW' AS q, FORMATDATETIME(CURRENT_TIMESTAMP,'MM-dd HH:mm','en','Asia/Seoul') AS now_kst, (SELECT value FROM settings WHERE key='active.paused_until') AS paused, (SELECT value FROM settings WHERE key='voice.muted_until') AS muted FROM (VALUES 1);
SELECT 'D' AS q, app_version, FORMATDATETIME(last_heartbeat,'MM-dd HH:mm','en','Asia/Seoul') AS hb FROM devices ORDER BY last_heartbeat DESC LIMIT 1;
SELECT 'I' AS q, i.id, i.block_id, i.status, i.level, i.reason, i.end_reason, FORMATDATETIME(i.started_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS st, FORMATDATETIME(i.ended_at,'HH:mm:ss','en','Asia/Seoul') AS en FROM interventions i WHERE i.started_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) ORDER BY i.id;
SELECT 'IL' AS q, l.intervention_id AS iid, l.block_id, l.level, l.reaction, FORMATDATETIME(l.at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS at FROM intervention_logs l WHERE l.at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) ORDER BY l.id;
SELECT 'B' AS q, b.id, b.kind, b.status, b.link_role, b.travel_direction AS dir, b.linked_fixed_block_id AS lnk, b.series_id AS ser, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(b.end_at,'HH:mm','en','Asia/Seoul') AS en, b.lead_minutes AS lead, b.interrupt_level AS lvl, b.version AS ver, FORMATDATETIME(b.actual_end,'HH:mm','en','Asia/Seoul') AS aend, FORMATDATETIME(b.updated_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS upd FROM blocks b WHERE b.status <> 'cancelled' AND b.end_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) AND b.start_at < DATEADD('HOUR', 4, CURRENT_TIMESTAMP) ORDER BY b.start_at;
SELECT 'BL' AS q, l.block_id, l.type, l.actor, FORMATDATETIME(l.at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS at FROM block_logs l WHERE l.at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) ORDER BY l.id;
SELECT 'PA' AS q, LEFT(result, 120) AS result, FORMATDATETIME(created_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS at FROM processed_actions WHERE created_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) ORDER BY created_at;
SELECT 'J' AS q, type, target_id, status, FORMATDATETIME(fire_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS fire FROM jobs WHERE fire_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) AND type IN ('reshow','escalate','start_intervention','expire','suspend_check','block_end','time_window_tick','travel_notice','dnd_release') ORDER BY fire_at;
SELECT 'W' AS q, window_id, FORMATDATETIME(occ_start,'MM-dd HH:mm','en','Asia/Seoul') AS occ, alarm_count, FORMATDATETIME(alarm_last_at,'HH:mm','en','Asia/Seoul') AS alarm_last, FORMATDATETIME(acked_at,'HH:mm','en','Asia/Seoul') AS acked, llm_count, FORMATDATETIME(ended_at,'MM-dd HH:mm','en','Asia/Seoul') AS ended FROM time_window_runs ORDER BY id DESC LIMIT 4;
SELECT 'U' AS q, t.id, t.trigger, t.status, FORMATDATETIME(t.started_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS st, t.tool_names FROM turns t WHERE t.started_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) AND t.trigger NOT IN ('memory_job') ORDER BY t.id;
SELECT 'M' AS q, m.id, m.role, m.kind, m.silent, m.intervention_id AS iid, m.source, FORMATDATETIME(m.created_at,'MM-dd HH:mm:ss','en','Asia/Seoul') AS at FROM messages m WHERE m.created_at >= DATEADD('HOUR', -16, CURRENT_TIMESTAMP) ORDER BY m.id;
SQL

while IFS= read -r q; do
  [ -n "$q" ] || continue
  "$JAVA" -cp "$H2" org.h2.tools.Shell -url "$URL" -user sa -password "" -sql "$q" >> "$WORK/out.txt" 2>&1 || echo "QUERY FAILED" >> "$WORK/out.txt"
done < "$WORK/queries.sql"

{ echo "ACTIONS_HTTP"; for f in /var/log/nginx/access.log; do [ -f "$f" ] && grep -h "POST /api/assistant/v1/actions" "$f"; done | awk '{print $4, $9}' | tail -40; } >> "$WORK/out.txt" 2>/dev/null || true
{ echo "ERR_LOG"; journalctl -u phonedrop --since "-16h" --no-pager 2>/dev/null | grep -E "ERROR|Exception" | sed -E 's/[가-힣]+/*/g' | cut -c1-240 | tail -15; } >> "$WORK/out.txt" || true

# 3000자씩 ::notice 주석으로 내보낸다(공개 annotations API 로 읽을 수 있다).
awk -v max=3000 '
  { line = $0; gsub(/\r/, "", line); gsub(/%/, "%25", line)
    if (length(buf) + length(line) + 3 > max) { print "::notice title=lat" n "::" buf; n++; buf = "" }
    buf = buf line "%0A" }
  END { if (buf != "") print "::notice title=lat" n "::" buf }' "$WORK/out.txt"
