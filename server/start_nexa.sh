#!/data/data/com.termux/files/usr/bin/bash
# Menjalankan Server Media NEXA (Python, port 5000) + TikTok Bridge (Node, port 8090).
cd "$(dirname "$0")"
[ -f ~/.nexa_env ] && source ~/.nexa_env
if [ -z "$NEXA_TOKEN" ]; then echo "Isi NEXA_TOKEN di ~/.nexa_env dulu"; exit 1; fi
command -v termux-wake-lock >/dev/null && termux-wake-lock
export HOST=${HOST:-127.0.0.1} PORT=${PORT:-5000}
mkdir -p logs

node tiktok/server.js > logs/tiktok.log 2>&1 &
TT_PID=$!
echo "TikTok bridge  → ws://127.0.0.1:${TT_PORT:-8090}   (log: server/logs/tiktok.log)"
trap 'kill $TT_PID 2>/dev/null; command -v termux-wake-unlock >/dev/null && termux-wake-unlock' EXIT

echo "Server media   → http://$HOST:$PORT"
python nexa_server.py
