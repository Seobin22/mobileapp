#!/usr/bin/env bash
# 저녁 이동 블록·일정 끝 알람 조사(읽기 전용, 제목·내용 없음): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
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
SELECT 'B' AS q, b.id, b.kind, b.status, b.link_role, b.travel_direction AS dir, b.linked_fixed_block_id AS linked, b.series_id AS ser, b.task_id, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(b.end_at,'HH:mm','en','Asia/Seoul') AS en, b.lead_minutes AS lead, b.buffer_after_min AS buf, b.interrupt_level AS lvl, b.lead_includes_travel AS lit, (b.lat IS NOT NULL) AS has_place, FORMATDATETIME(b.created_at,'MM-dd HH:mm','en','Asia/Seoul') AS created, FORMATDATETIME(b.updated_at,'MM-dd HH:mm','en','Asia/Seoul') AS upd FROM blocks b WHERE b.start_at >= DATEADD('HOUR', 7, CAST(CURRENT_DATE AS TIMESTAMP WITH TIME ZONE)) AND b.start_at < DATEADD('HOUR', 16, CAST(CURRENT_DATE AS TIMESTAMP WITH TIME ZONE)) ORDER BY b.start_at, b.id;
SELECT 'BT' AS q, t.block_id, t.direction, t.mode, t.minutes, t.source, FORMATDATETIME(t.computed_at,'MM-dd HH:mm','en','Asia/Seoul') AS at FROM block_travel t JOIN blocks b ON b.id=t.block_id WHERE b.start_at >= DATEADD('HOUR', 7, CAST(CURRENT_DATE AS TIMESTAMP WITH TIME ZONE)) AND b.start_at < DATEADD('HOUR', 16, CAST(CURRENT_DATE AS TIMESTAMP WITH TIME ZONE)) ORDER BY t.id;
SELECT 'W' AS q, id, status, task_id, created_by_turn AS turn_id, FORMATDATETIME(fire_at,'MM-dd HH:mm','en','Asia/Seoul') AS fire, FORMATDATETIME(created_at,'MM-dd HH:mm','en','Asia/Seoul') AS created FROM wakeups WHERE created_at >= DATEADD('HOUR', -20, CURRENT_TIMESTAMP) ORDER BY id;
SELECT 'U' AS q, t.id, t.trigger, t.status, FORMATDATETIME(t.started_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, t.tool_names FROM turns t WHERE t.started_at >= DATEADD('HOUR', -20, CURRENT_TIMESTAMP) AND t.trigger NOT IN ('memory_job','voice','active_window') ORDER BY t.id;
SELECT 'C' AS q, c.turn_id, c.entity, c.entity_id, FORMATDATETIME(c.created_at,'MM-dd HH:mm','en','Asia/Seoul') AS at, (c.before_value IS NULL) AS created_new FROM changes c WHERE c.created_at >= DATEADD('HOUR', -20, CURRENT_TIMESTAMP) ORDER BY c.id;
SELECT 'I' AS q, i.id, i.block_id, i.status, i.level, i.reason, i.end_reason, FORMATDATETIME(i.started_at,'MM-dd HH:mm','en','Asia/Seoul') AS st, FORMATDATETIME(i.ended_at,'HH:mm','en','Asia/Seoul') AS en FROM interventions i WHERE i.started_at >= DATEADD('HOUR', -12, CURRENT_TIMESTAMP) ORDER BY i.id;
SELECT 'H' AS q, held_block_id, held_window_id, COUNT(*) AS n FROM messages WHERE (held_block_id IS NOT NULL OR held_window_id IS NOT NULL) GROUP BY held_block_id, held_window_id;
SELECT 'J' AS q, type, target_id, status, FORMATDATETIME(fire_at,'MM-dd HH:mm','en','Asia/Seoul') AS fire FROM jobs WHERE fire_at >= DATEADD('HOUR', -6, CURRENT_TIMESTAMP) AND fire_at < DATEADD('HOUR', 6, CURRENT_TIMESTAMP) AND type NOT IN ('turn','time_window_tick') ORDER BY fire_at;
SELECT 'S' AS q, s.id, s.lead_minutes, s.buffer_after_min, s.interrupt_level FROM fixed_series s WHERE s.id IN (8, 12);
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
