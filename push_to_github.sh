#!/usr/bin/env bash
# Unggah proyek ini ke https://github.com/gurundanatan-sketch/NEXA-AI-BY-NATAN
# Termux:  pkg install git gh && gh auth login   (atau pakai Personal Access Token saat diminta password)
set -e
cd "$(dirname "$0")"
git remote remove origin 2>/dev/null || true
git remote add origin https://github.com/gurundanatan-sketch/NEXA-AI-BY-NATAN.git
git branch -M main
git push -u origin main
echo "Selesai. Buka tab Actions di GitHub untuk melihat build APK."
