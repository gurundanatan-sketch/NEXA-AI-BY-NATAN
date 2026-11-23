#!/data/data/com.termux/files/usr/bin/bash
# Pasang semua kebutuhan server NEXA di Termux (sekali saja).
set -e
cd "$(dirname "$0")"
echo "== Memasang paket Termux =="
pkg update -y
pkg install -y python nodejs ffmpeg git termux-api || pkg install -y python nodejs ffmpeg git
echo "== Memasang modul Python =="
pip install -r requirements.txt
echo "== Memasang modul Node (TikTok bridge) =="
(cd tiktok && npm install --omit=dev)
if [ ! -f ~/.nexa_env ]; then
  cp nexa_env.example ~/.nexa_env
  echo ""
  echo ">> Buka dan isi kunci API Anda:  nano ~/.nexa_env"
fi
echo "Selesai. Jalankan:  bash start_nexa.sh"
