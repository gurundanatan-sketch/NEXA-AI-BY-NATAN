#!/data/data/com.termux/files/usr/bin/bash
# =====================================================================
#  Tanda tangan pribadi untuk NEXA AI BY NATAN
#  Sekali saja. Setelah ini setiap APK baru bisa langsung menimpa
#  aplikasi lama TANPA uninstall.
#
#  Pakai:  bash tools/setup_signing.sh                 (buat kunci baru)
#          bash tools/setup_signing.sh /path/kunci.jks (pakai kunci yang sudah ada)
# =====================================================================
set -e
cd "$(dirname "$0")/.."

command -v keytool >/dev/null || pkg install -y openjdk-17
command -v gh >/dev/null || pkg install -y gh

KS="${1:-$HOME/nexa-release.jks}"

if [ ! -f "$KS" ]; then
  echo "== Membuat kunci tanda tangan baru: $KS"
  ALIAS="nexa"
  while true; do
    read -s -p "Buat password kunci (min. 6 karakter): " PASS; echo
    [ ${#PASS} -ge 6 ] && break
    echo "Terlalu pendek."
  done
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Natan, O=NEXA AI BY NATAN, C=ID"
  KEYPASS="$PASS"
else
  echo "== Memakai kunci yang sudah ada: $KS"
  read -p "Alias kunci di dalam file ini: " ALIAS
  read -s -p "Password file kunci: " PASS; echo
  read -s -p "Password alias (Enter jika sama): " KEYPASS; echo
  KEYPASS="${KEYPASS:-$PASS}"
  keytool -list -keystore "$KS" -storepass "$PASS" -alias "$ALIAS" >/dev/null \
    || { echo "Alias atau password salah."; exit 1; }
fi

echo "== Menyimpan kunci ke GitHub Secrets (repo ini)…"
REPO="gurundanatan-sketch/NEXA-AI-BY-NATAN"
base64 -w0 "$KS" | gh secret set NEXA_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$PASS"    | gh secret set NEXA_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$ALIAS"   | gh secret set NEXA_KEY_ALIAS -R "$REPO"
printf '%s' "$KEYPASS" | gh secret set NEXA_KEY_PASSWORD -R "$REPO"

if [ -d "$HOME/storage/downloads" ]; then
  cp "$KS" "$HOME/storage/downloads/NEXA-release-KEY-BACKUP.jks"
  echo "Cadangan kunci: Download/NEXA-release-KEY-BACKUP.jks"
fi
echo ""
echo "✓ Selesai. Build berikutnya otomatis ditandatangani kunci pribadi Anda."
echo "!! SIMPAN file kunci & password ini. Kalau hilang, update tidak bisa menimpa aplikasi lama."
echo "   Jalankan build:  gh workflow run build-apk.yml -R $REPO"
