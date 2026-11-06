#!/usr/bin/env bash
# 실행 비서가 취소한 블록 조회(읽기 전용): DB 파일 복사본에서 블록 id·시각·상태만 읽는다(제목·메시지 내용은 출력하지 않음).
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
SELECT 'A_CANCEL_SUMMARY' AS q, b.kind, t.trigger, c.undone, COUNT(*) AS n FROM changes c JOIN turns t ON t.id=c.turn_id JOIN blocks b ON b.id=c.entity_id WHERE c.entity='block' AND c.after_value LIKE '%"status":"cancelled"%' AND c.before_value IS NOT NULL AND c.before_value NOT LIKE '%"status":"cancelled"%' GROUP BY b.kind, t.trigger, c.undone ORDER BY n DESC;
SELECT 'B_DROPPED' AS q, b.id AS block_id, b.kind, b.task_id, b.goal_id, FORMATDATETIME(b.start_at,'MM-dd HH:mm','en','Asia/Seoul') AS planned_at, (DATEDIFF('MINUTE', b.start_at, b.end_at)) AS mins, FORMATDATETIME(c.created_at,'MM-dd HH:mm','en','Asia/Seoul') AS cancelled_at, c.turn_id, t.trigger, c.undone, b.status AS now_status, tk.status AS task_status, (SELECT COUNT(*) FROM changes c2 JOIN blocks b2 ON b2.id=c2.entity_id WHERE c2.turn_id=c.turn_id AND c2.entity='block' AND c2.before_value IS NULL AND b2.task_id=b.task_id) AS same_turn_new, (SELECT COUNT(*) FROM blocks b3 WHERE b3.task_id=b.task_id AND b3.id<>b.id AND b3.status<>'cancelled' AND b3.created_at>=c.created_at) AS later_blocks, (SELECT COUNT(*) FROM blocks b4 WHERE b4.task_id=b.task_id AND b4.id<>b.id AND b4.status IN ('done','partial') AND b4.created_at>=c.created_at) AS later_done FROM changes c JOIN turns t ON t.id=c.turn_id JOIN blocks b ON b.id=c.entity_id LEFT JOIN tasks tk ON tk.id=b.task_id WHERE c.entity='block' AND c.after_value LIKE '%"status":"cancelled"%' AND c.before_value IS NOT NULL AND c.before_value NOT LIKE '%"status":"cancelled"%' AND (b.kind='fixed' OR b.task_id IS NULL OR NOT EXISTS (SELECT 1 FROM changes c2 JOIN blocks b2 ON b2.id=c2.entity_id WHERE c2.turn_id=c.turn_id AND c2.entity='block' AND c2.before_value IS NULL AND b2.task_id=b.task_id)) ORDER BY c.created_at;
SELECT 'C_OTHER_CANCELLED' AS q, b.kind, COUNT(*) AS n FROM blocks b WHERE b.status='cancelled' AND NOT EXISTS (SELECT 1 FROM changes c WHERE c.entity='block' AND c.entity_id=b.id AND c.after_value LIKE '%"status":"cancelled"%') AND (b.link_role IS NULL OR b.link_role<>'travel') GROUP BY b.kind;
SELECT 'D_TOTAL_BLOCKS' AS q, kind, status, COUNT(*) AS n FROM blocks WHERE (link_role IS NULL OR link_role<>'travel') GROUP BY kind, status ORDER BY kind, status;
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
