#!/usr/bin/env bash
# NEXA — buat repo publik "nexa-server" (TANPA kunci apa pun) untuk tombol "Deploy to Cloudflare".
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)/server/cloudflare"
OWNER="$(gh api user -q .login)"
TMP="$HOME/nexa-server"
rm -rf "$TMP"; mkdir -p "$TMP"; cp -a "$SRC/." "$TMP/"
cd "$TMP"
git init -q -b main
git add -A
git -c user.name=Natan -c user.email=nexa@local commit -qm "NEXA server"
if gh repo view "$OWNER/nexa-server" >/dev/null 2>&1; then
  git remote add origin "https://github.com/$OWNER/nexa-server.git"
  git push -q -f origin main
else
  gh repo create nexa-server --public --source=. --push >/dev/null
fi
LINK="https://deploy.workers.cloudflare.com/?url=https://github.com/$OWNER/nexa-server"
echo
echo "✓ Repo server siap (tanpa kunci)."
echo "👉 Buka link ini di Chrome lalu tekan Deploy:"
echo "$LINK"
command -v termux-open-url >/dev/null && termux-open-url "$LINK" || true
