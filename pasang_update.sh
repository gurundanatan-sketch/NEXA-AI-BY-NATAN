#!/usr/bin/env bash
# NEXA AI BY NATAN — pasang full master backup ini ke repo GitHub Anda (Termux).
# Jalankan dari folder hasil ekstrak:  bash pasang_update.sh
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/NEXA-AI-BY-NATAN"
REPO="gurundanatan-sketch/NEXA-AI-BY-NATAN"

if [ ! -d "$DEST/.git" ]; then
  echo "== Mengambil repo dari GitHub…"
  gh repo clone "$REPO" "$DEST"
fi
cd "$DEST"
git pull -q --rebase origin main || true

echo "== Menyalin file versi baru…"
# hapus kode lama (kecuali .git) supaya file yang sudah tidak dipakai ikut terhapus
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC/." "$DEST/"
chmod +x gradlew tools/*.sh push_to_github.sh pasang_update.sh 2>/dev/null || true

git add -A
git -c user.name=Natan -c user.email=nexa@local commit -qm "NEXA AI BY NATAN v7.2 (full master)" || echo "(tidak ada perubahan)"
git push origin main
echo "✓ v7.2 terkirim ke GitHub. Build APK berjalan otomatis di tab Actions."
