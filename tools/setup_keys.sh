#!/usr/bin/env bash
# NEXA AI BY NATAN — tanam kunci API otomatis ke dalam APK (lewat GitHub Secrets).
# Jalankan sekali di Termux:  bash tools/setup_keys.sh
# Kunci TIDAK ditulis ke kode/repo. Kunci hanya disimpan sebagai GitHub Secrets,
# lalu dimasukkan ke APK saat build. Setelah itu aplikasi langsung jalan tanpa isi kunci.
set -e
REPO="gurundanatan-sketch/NEXA-AI-BY-NATAN"
command -v gh >/dev/null || pkg install -y gh

echo "== Tempel kunci lalu tekan Enter. Tulisan TIDAK terlihat saat ditempel (normal)."
echo "== Kosongkan (langsung Enter) untuk melewati kunci yang tidak dipakai."
echo

ask() {   # ask <SECRET_NAME> <label>
  local v
  read -r -s -p "$2: " v; echo
  v="$(printf '%s' "$v" | tr -d '[:space:]')"
  if [ -n "$v" ]; then
    printf '%s' "$v" | gh secret set "$1" -R "$REPO" >/dev/null && echo "   ✓ $1 tersimpan"
  else
    echo "   – $1 dilewati"
  fi
}

ask NEXA_GEMINI_KEY "1) Gemini API key (AI Studio)"
ask NEXA_OPENAI_KEY "2) OpenAI API key"
ask NEXA_FISH_KEY   "3) Fish Audio API key"
ask NEXA_TIKTOK_KEY "4) TikTok LIVE key (Euler Stream / TikTool, opsional)"

echo
echo "✓ Selesai. Build APK baru:"
echo "   gh workflow run build-apk.yml -R $REPO"
echo "!! APK berisi kunci Anda — jangan bagikan file APK ke orang lain."
