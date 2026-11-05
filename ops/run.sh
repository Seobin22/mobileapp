#!/usr/bin/env bash
# 실행 비서 연결 블록 사용 현황 조회(읽기 전용): DB 파일 복사본에서 turn·메시지 시각과 토큰만 읽는다.
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
SELECT 'LINKED_ALL' AS q, COUNT(*) AS n, SUM(CASE WHEN status <> 'cancelled' THEN 1 ELSE 0 END) AS not_cancelled FROM blocks WHERE linked_fixed_block_id IS NOT NULL;
SELECT 'LINKED_LIST' AS q, b.id, FORMATDATETIME(b.created_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS made, FORMATDATETIME(b.start_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS starts, b.status, b.linked_fixed_block_id AS fixed_id, FORMATDATETIME(f.start_at, 'MM-dd HH:mm', 'en', 'Asia/Seoul') AS fixed_starts FROM blocks b LEFT JOIN blocks f ON f.id = b.linked_fixed_block_id WHERE b.linked_fixed_block_id IS NOT NULL ORDER BY b.id DESC LIMIT 15;
SELECT 'FIXED14' AS q, COUNT(*) AS fixed_next_14d, SUM(CASE WHEN memo <> '' THEN 1 ELSE 0 END) AS with_once_memo, SUM(CASE WHEN location IS NOT NULL AND location <> '' THEN 1 ELSE 0 END) AS with_location, SUM(CASE WHEN series_id IS NULL THEN 1 ELSE 0 END) AS single FROM blocks WHERE kind = 'fixed' AND status IN ('planned','nudging') AND start_at > CURRENT_TIMESTAMP AND start_at < DATEADD('DAY', 14, CURRENT_TIMESTAMP);
SELECT 'SERIESMEMO' AS q, COUNT(*) AS active, SUM(CASE WHEN memo <> '' THEN 1 ELSE 0 END) AS with_memo FROM fixed_series WHERE active = TRUE;
SELECT 'TURNS' AS q, trigger, COUNT(*) AS n, SUM(CASE WHEN tool_names LIKE '%set_schedule%' THEN 1 ELSE 0 END) AS with_set_schedule FROM turns WHERE started_at >= TIMESTAMP WITH TIME ZONE '2026-11-03 00:00:00+09:00' GROUP BY trigger ORDER BY n DESC;
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
