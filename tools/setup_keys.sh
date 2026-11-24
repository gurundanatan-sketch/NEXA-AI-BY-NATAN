#!/usr/bin/env bash
# NEXA AI BY NATAN — MODE MANDIRI: tanam kunci API ke aplikasi lewat GitHub Secrets (dari Termux).
# Pemakaian:  bash tools/setup_keys.sh
# Kosongkan (langsung Enter) = kunci lama tetap dipakai.
set -e
REPO="gurundanatan-sketch/NEXA-AI-BY-NATAN"
for p in curl gh; do command -v "$p" >/dev/null || pkg install -y "$p"; done

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
t_gemini() { [ "$(code -H "x-goog-api-key: $1" "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")" = 200 ]; }
t_openai() { [ "$(code -H "Authorization: Bearer $1" "https://api.openai.com/v1/models")" = 200 ]; }
t_fish()   { [ "$(code -H "Authorization: Bearer $1" "https://api.fish.audio/model?page_size=1")" = 200 ]; }
t_none()   { true; }

ask() {  # ask SECRET label test
  local v
  while true; do
    read -r -s -p "$2: " v; echo
    v="$(printf '%s' "$v" | tr -d '[:space:]')"
    if [ -z "$v" ]; then echo "   – dilewati (kunci lama tetap dipakai)"; return; fi
    if "$3" "$v"; then
      printf '%s' "$v" | gh secret set "$1" -R "$REPO" >/dev/null && echo "   ✓ kunci valid & tersimpan"
      return
    fi
    echo "   ✗ Kunci DITOLAK penyedia. Salin ulang lalu tempel lagi (atau Enter untuk lewati)."
  done
}

echo "== Tempel kunci lalu Enter. Tulisan TIDAK terlihat (normal). Kosongkan = lewati."
ask NEXA_GEMINI_KEY "1) Gemini API key (aistudio.google.com/apikey)" t_gemini
ask NEXA_OPENAI_KEY "2) OpenAI API key (platform.openai.com/api-keys, diawali sk-)" t_openai
ask NEXA_FISH_KEY   "3) Fish Audio API key (fish.audio)" t_fish
ask NEXA_TIKTOK_KEY "4) TikTok LIVE key (eulerstream.com / tik.tools, opsional)" t_none

# Mode mandiri: matikan alamat server lama (bila pernah diisi)
gh secret delete NEXA_SERVER_URL -R "$REPO" >/dev/null 2>&1 || true
gh secret delete NEXA_APP_TOKEN -R "$REPO" >/dev/null 2>&1 || true

# Siapkan rahasia kode Premium (sekali saja)
bash "$(dirname "$0")/buat_kode.sh" --init

gh workflow run build-apk.yml -R "$REPO" >/dev/null && echo "✓ Build APK baru dimulai (±5 menit)."
echo "!! APK berisi kunci Anda — kunci bisa dibongkar dari APK. Pantau pemakaian di dashboard penyedia."
