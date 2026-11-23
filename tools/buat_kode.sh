#!/usr/bin/env bash
# NEXA AI BY NATAN — buat KODE AKTIVASI PREMIUM dari Termux.
# Pemakaian:  bash tools/buat_kode.sh
#   (atau langsung: bash tools/buat_kode.sh <ID_PERANGKAT> <jenis 1/2/3> [hari])
# ID perangkat dilihat pengguna di aplikasi: Akun → Langganan Premium → "ID perangkat".
set -e
CONF="$HOME/.nexa_server"
[ -f "$CONF" ] && . "$CONF"
if [ -z "$PREMIUM_SECRET" ]; then
  echo "✗ Server premium belum diatur. Jalankan dulu: bash tools/setup_server.sh"; exit 1
fi
command -v openssl >/dev/null || pkg install -y openssl-tool

DEV="$1"; JENIS="$2"; HARI="$3"
if [ -z "$DEV" ]; then
  read -r -p "ID perangkat pengguna: " DEV
fi
DEV="$(printf '%s' "$DEV" | tr -d '[:space:]')"
[ -z "$DEV" ] && { echo "✗ ID perangkat kosong"; exit 1; }

if [ -z "$JENIS" ]; then
  echo "Jenis kode:"
  echo "  1) Premium berbayar — 1 bulan (30 hari)"
  echo "  2) Premium gratis / hadiah — jumlah hari bebas (0 = selamanya)"
  echo "  3) PEMBUAT APLIKASI — selamanya (akses semua + kotak saran)"
  read -r -p "Pilih 1/2/3: " JENIS
fi

NOW=$(( $(date +%s) / 86400 ))
case "$JENIS" in
  1) T=P; EXP=$(( NOW + 30 )); KET="Premium 30 hari" ;;
  2) T=G
     [ -z "$HARI" ] && read -r -p "Berapa hari? (0 = selamanya): " HARI
     HARI="${HARI:-30}"
     if [ "$HARI" = "0" ]; then EXP=0; KET="Premium hadiah — selamanya"; else EXP=$(( NOW + HARI )); KET="Premium hadiah $HARI hari"; fi ;;
  3) T=O; EXP=0; KET="Pembuat aplikasi — selamanya" ;;
  *) echo "✗ Pilihan tidak dikenal"; exit 1 ;;
esac

SIG=$(printf '%s' "$DEV|$T|$EXP" | openssl dgst -sha256 -hmac "$PREMIUM_SECRET" | awk '{print $NF}' | cut -c1-16 | tr 'a-f' 'A-F')
KODE="NX-$T$EXP-$SIG"
if [ "$EXP" = "0" ]; then SAMPAI="selamanya"; else SAMPAI=$(date -d "@$(( EXP * 86400 ))" +%d-%m-%Y 2>/dev/null || echo "hari ke-$EXP"); fi

echo
echo "================ KODE AKTIVASI NEXA ================"
echo "  $KODE"
echo "  Jenis   : $KET"
echo "  Berlaku : sampai $SAMPAI"
echo "  Perangkat: $DEV"
echo "===================================================="
echo "Kirim kode ini ke pengguna. Tempel di: Akun → Langganan Premium → Aktifkan."
echo "$(date '+%F %T')  $KODE  $KET  $DEV" >> "$HOME/nexa_kode_premium.txt"
