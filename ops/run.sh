#!/usr/bin/env bash
# 실행 비서 푸시를 Firebase(FCM)로 바꾼다: systemd drop-in 으로 환경변수를 넣고 재시작한다.
# 앱이 정상 기동하지 않으면 drop-in 을 지우고 원래(fake) 상태로 되돌린 뒤 실패로 끝낸다.
set -euo pipefail

DROPIN_DIR=/etc/systemd/system/phonedrop.service.d
DROPIN=$DROPIN_DIR/firebase.conf

mkdir -p "$DROPIN_DIR"
cat > "$DROPIN" <<'EOF'
[Service]
Environment=ASSISTANT_PUSH_MODE=firebase
Environment=ASSISTANT_FIREBASE_CREDENTIALS=/root/data/assistant/firebase.json
EOF

wait_up() {
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null http://127.0.0.1:8080/; then return 0; fi
    sleep 2
  done
  return 1
}

since=$(date '+%Y-%m-%d %H:%M:%S')
systemctl daemon-reload
systemctl restart phonedrop

if wait_up && systemctl is-active --quiet phonedrop \
   && journalctl -u phonedrop --since "$since" --no-pager | grep -q "assistant push: firebase"; then
  echo "phonedrop running with firebase push"
  exit 0
fi

echo "firebase push did not start; rolling back to fake push"
journalctl -u phonedrop --since "$since" --no-pager | grep -iE "firebase|error|exception" | grep -v private_key | tail -20 || true
rm -f "$DROPIN"
systemctl daemon-reload
systemctl restart phonedrop
wait_up || true
exit 1
