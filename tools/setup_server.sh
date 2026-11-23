#!/usr/bin/env bash
# NEXA AI BY NATAN — pasang / perbarui SERVER PRIBADI NEXA dari Termux.
# Server: Cloudflare Workers (gratis, online 24 jam, tidak butuh HP menyala).
# Semua kunci API (Gemini, OpenAI, Fish Audio, TikTok) disimpan di server,
# jadi siapa pun yang memasang APK NEXA langsung bisa memakai semua fitur
# tanpa mengisi kunci, dan kunci Anda tidak ikut tersebar di dalam APK.
#
# Pemakaian:   bash tools/setup_server.sh
# Jalankan lagi kapan saja untuk mengganti kunci (kosongkan = kunci lama tetap).
set -e
REPO="gurundanatan-sketch/NEXA-AI-BY-NATAN"
NAME="nexa-server"
CONF="$HOME/.nexa_server"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKER="$DIR/server/cloudflare/worker.js"
API="https://api.cloudflare.com/client/v4"
TMP="${PREFIX:-/tmp}/tmp/nexa-server"; mkdir -p "$TMP"

for p in curl jq gh; do command -v "$p" >/dev/null || pkg install -y "$p"; done
[ -f "$WORKER" ] || { echo "✗ File server tidak ditemukan: $WORKER"; exit 1; }
[ -f "$CONF" ] && . "$CONF"

echo "=================== SERVER NEXA (Cloudflare) ==================="
if [ -z "$CF_TOKEN" ]; then
  echo "Butuh token Cloudflare (sekali saja):"
  echo "  1. Daftar/masuk di dash.cloudflare.com (gratis)"
  echo "  2. Profil (kanan atas) → API Tokens → Create Token"
  echo "  3. Pilih template 'Edit Cloudflare Workers' → Use template"
  echo "  4. Account Resources: pilih akun Anda → Continue → Create Token → salin"
  read -r -s -p "Tempel token Cloudflare: " CF_TOKEN; echo
  CF_TOKEN="$(printf '%s' "$CF_TOKEN" | tr -d '[:space:]')"
fi
cf() { curl -s -H "Authorization: Bearer $CF_TOKEN" "$@"; }

ACC="$(cf "$API/accounts" | jq -r '.result[0].id // empty')"
if [ -z "$ACC" ]; then
  echo "✗ Token Cloudflare ditolak / izinnya kurang. Buat ulang dengan template 'Edit Cloudflare Workers'."
  rm -f "$CONF"; exit 1
fi
echo "✓ Akun Cloudflare tersambung"

[ -z "$APP_TOKEN" ] && APP_TOKEN="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
# Rahasia kode premium & sesi login — JANGAN dibagikan. Dipakai tools/buat_kode.sh.
[ -z "$PREMIUM_SECRET" ] && PREMIUM_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
umask 077
saveconf() {
  printf 'CF_TOKEN=%q\nAPP_TOKEN=%q\nPREMIUM_SECRET=%q\nSERVER_URL=%q\n' "$CF_TOKEN" "$APP_TOKEN" "$PREMIUM_SECRET" "$1" > "$CONF"
}
saveconf "$SERVER_URL"

# ---------------------------------------------------------------- database akun (KV)
KV_ID="$(cf "$API/accounts/$ACC/storage/kv/namespaces?per_page=100" | jq -r '.result[]? | select(.title=="nexa-db") | .id' | head -1)"
if [ -z "$KV_ID" ]; then
  KV_ID="$(cf -X POST "$API/accounts/$ACC/storage/kv/namespaces" -H "Content-Type: application/json" \
    --data '{"title":"nexa-db"}' | jq -r '.result.id // empty')"
fi
[ -z "$KV_ID" ] && { echo "✗ Gagal membuat database akun (KV). Pastikan token punya izin Workers KV Storage: Edit."; exit 1; }
echo "✓ Database akun siap"

# ---------------------------------------------------------------- unggah server
echo "== Mengunggah server NEXA…"
jq -n --arg kv "$KV_ID" '{main_module:"worker.js",compatibility_date:"2025-01-01",keep_bindings:["secret_text"],
  bindings:[{type:"kv_namespace",name:"DB",namespace_id:$kv}]}' > "$TMP/meta.json"
R="$(cf -X PUT "$API/accounts/$ACC/workers/scripts/$NAME" \
  -F "metadata=@$TMP/meta.json;type=application/json" \
  -F "worker.js=@$WORKER;type=application/javascript+module")"
if [ "$(echo "$R" | jq -r '.success')" != "true" ]; then
  echo "✗ Gagal mengunggah server:"; echo "$R" | jq -r '.errors[]?.message'; exit 1
fi
echo "✓ Server terunggah"

setsec() {
  local body
  body="$(jq -n --arg n "$1" --arg t "$2" '{name:$n,text:$t,type:"secret_text"}')"
  if [ "$(cf -X PUT "$API/accounts/$ACC/workers/scripts/$NAME/secrets" -H "Content-Type: application/json" --data "$body" | jq -r '.success')" = "true" ]; then
    echo "   ✓ $1 tersimpan di server"
  else
    echo "   ✗ $1 gagal disimpan"
  fi
}
setsec APP_TOKEN "$APP_TOKEN" >/dev/null
setsec PREMIUM_SECRET "$PREMIUM_SECRET" >/dev/null

# ---------------------------------------------------------------- kunci API
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
    if [ -z "$v" ]; then echo "   – dilewati (kunci lama di server tetap dipakai)"; return; fi
    if "$3" "$v"; then echo "   ✓ kunci valid"; setsec "$1" "$v"; return; fi
    echo "   ✗ Kunci DITOLAK oleh penyedia. Salin ulang lalu tempel lagi (atau Enter untuk lewati)."
  done
}

echo
echo "== Tempel kunci lalu Enter. Tulisan TIDAK terlihat (normal). Kosongkan = lewati."
ask GEMINI_KEY "1) Gemini API key (aistudio.google.com/apikey)" t_gemini
ask OPENAI_KEY "2) OpenAI API key (platform.openai.com/api-keys, diawali sk-)" t_openai
ask FISH_KEY   "3) Fish Audio API key (fish.audio)" t_fish
ask TIKTOK_KEY "4) TikTok LIVE key (eulerstream.com / tik.tools, opsional)" t_none

# ---------------------------------------------------------------- alamat publik
SUB="$(cf "$API/accounts/$ACC/workers/subdomain" | jq -r '.result.subdomain // empty')"
if [ -z "$SUB" ]; then
  SUB="nexa-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cf -X PUT "$API/accounts/$ACC/workers/subdomain" -H "Content-Type: application/json" --data "{\"subdomain\":\"$SUB\"}" >/dev/null
fi
cf -X POST "$API/accounts/$ACC/workers/scripts/$NAME/subdomain" -H "Content-Type: application/json" \
  --data '{"enabled":true,"previews_enabled":false}' >/dev/null
URL="https://$NAME.$SUB.workers.dev"

echo
echo "== Mengecek server (alamat baru bisa butuh ±1 menit)…"
OK=""
for i in 1 2 3 4 5 6 7 8; do
  H="$(curl -s -H "x-nexa-app: $APP_TOKEN" "$URL/health?deep=1" || true)"
  if echo "$H" | jq -e '.gemini' >/dev/null 2>&1; then OK=1; break; fi
  sleep 10
done
if [ -n "$OK" ]; then
  echo "$H" | jq -r 'to_entries[] | "   \(if .value=="ok" then "✅" elif .value=="kosong" then "⚪" else "❌" end) \(.key): \(.value)"'
else
  echo "   (server belum bisa dicek sekarang, coba lagi nanti: cek koneksi dari aplikasi)"
fi

# ---------------------------------------------------------------- aplikasi
OLD_URL="$SERVER_URL"
saveconf "$URL"
if [ "$OLD_URL" = "$URL" ]; then
  echo
  echo "✓ Kunci di server diperbarui. Aplikasi TIDAK perlu diupdate — langsung berlaku."
  exit 0
fi

echo
echo "== Menghubungkan aplikasi ke server…"
printf '%s' "$URL" | gh secret set NEXA_SERVER_URL -R "$REPO" >/dev/null && echo "   ✓ alamat server tersimpan"
printf '%s' "$APP_TOKEN" | gh secret set NEXA_APP_TOKEN -R "$REPO" >/dev/null && echo "   ✓ token aplikasi tersimpan"
# Kunci lama yang dulu ditanam di APK dihapus (sekarang semua lewat server).
for s in NEXA_GEMINI_KEY NEXA_OPENAI_KEY NEXA_FISH_KEY NEXA_TIKTOK_KEY; do
  gh secret delete "$s" -R "$REPO" >/dev/null 2>&1 || true
done
gh workflow run build-apk.yml -R "$REPO" >/dev/null && echo "   ✓ build APK baru dimulai"
echo
echo "✓ Selesai. Server: $URL"
echo "  Buat kode Premium untuk pengguna/pembuat:  bash tools/buat_kode.sh"
echo "  Pantau build:  gh run watch \$(gh run list -L 1 --json databaseId -q '.[0].databaseId')"
