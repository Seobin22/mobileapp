#!/usr/bin/env bash
# 실행 비서 이동 블록 이상 조회(읽기 전용): DB 파일 복사본에서 turn·메시지 시각과 토큰만 읽는다.
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
SELECT 'STALE_ARRIVE' AS q, t.id, t.status, FORMATDATETIME(t.end_at,'MM-dd HH:mm','en','Asia/Seoul') AS t_end, f.id AS fid, f.status AS f_status, FORMATDATETIME(f.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS f_start FROM blocks t JOIN blocks f ON f.id = t.linked_fixed_block_id WHERE t.link_role='travel' AND t.travel_direction='arrive' AND t.status IN ('planned','nudging','started') AND (t.end_at <> f.start_at OR f.status IN ('cancelled','missed','done','partial'));
SELECT 'STALE_HOME' AS q, t.id, t.status, FORMATDATETIME(t.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS t_start, f.id AS fid, f.status AS f_status, FORMATDATETIME(f.end_at,'MM-dd HH:mm','en','Asia/Seoul') AS f_end FROM blocks t JOIN blocks f ON f.id = t.linked_fixed_block_id WHERE t.link_role='travel' AND t.travel_direction='home' AND t.status IN ('planned','nudging','started') AND (t.start_at < f.end_at OR f.status IN ('cancelled','missed') OR EXISTS (SELECT 1 FROM blocks g WHERE g.kind='fixed' AND g.status IN ('planned','nudging','started') AND g.lat IS NOT NULL AND g.start_at >= f.end_at AND CAST(g.start_at AT TIME ZONE 'Asia/Seoul' AS DATE) = CAST(f.start_at AT TIME ZONE 'Asia/Seoul' AS DATE)));
SELECT 'NOTICE_STALE' AS q, COUNT(*) AS n FROM jobs j JOIN blocks t ON t.id = j.target_id WHERE j.type='travel_notice' AND j.status='scheduled' AND (t.status NOT IN ('planned','nudging','started') OR j.expected_version <> t.version);
SELECT 'NO_ALARM_FIXED' AS q, b.id, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS starts, b.lat IS NOT NULL AS has_coords FROM blocks b WHERE b.kind='fixed' AND b.status='planned' AND b.start_at > CURRENT_TIMESTAMP AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.type='start_intervention' AND j.target_id=b.id AND j.status='scheduled') AND NOT EXISTS (SELECT 1 FROM blocks t WHERE t.linked_fixed_block_id=b.id AND t.link_role='travel' AND t.travel_direction='arrive' AND t.status IN ('planned','nudging'));
SELECT 'TRAVEL_IV' AS q, i.status, i.end_reason, COUNT(*) AS n, MAX(FORMATDATETIME(i.started_at,'MM-dd HH:mm','en','Asia/Seoul')) AS last FROM interventions i JOIN blocks t ON t.id=i.block_id WHERE t.link_role='travel' GROUP BY i.status, i.end_reason;
SELECT 'TRAVEL_LOGS' AS q, type, actor, COUNT(*) AS n FROM block_logs l JOIN blocks t ON t.id=l.block_id WHERE t.link_role='travel' GROUP BY type, actor;
SELECT 'TALK_ON_TRAVEL' AS q, COUNT(*) AS n FROM turns WHERE trigger='intervention_talk' AND trigger_ref LIKE '%block_id%' AND CAST(REGEXP_SUBSTR(trigger_ref, '"block_id":([0-9]+)', 1, 1, '', 1) AS BIGINT) IN (SELECT id FROM blocks WHERE link_role='travel');
SELECT 'TURN_TOOLS' AS q, FORMATDATETIME(started_at,'MM-dd HH:mm','en','Asia/Seoul') AS kst, trigger, status, LEFT(tool_names,160) AS tools FROM turns WHERE started_at >= TIMESTAMP WITH TIME ZONE '2026-11-05 17:00:00+09:00' AND (tool_names LIKE '%move_block%' OR tool_names LIKE '%upsert_fixed%' OR tool_names LIKE '%extend_block%') ORDER BY id;
SELECT 'FIXED_LEAD' AS q, prep_set_by, lead_includes_travel, COUNT(*) AS n, SUM(CASE WHEN lead_minutes > 0 THEN 1 ELSE 0 END) AS with_lead FROM blocks WHERE kind='fixed' AND status='planned' AND start_at > CURRENT_TIMESTAMP GROUP BY prep_set_by, lead_includes_travel;
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
