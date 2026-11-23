#!/usr/bin/env bash
# Diganti oleh server pribadi NEXA: semua kunci API kini disimpan di server, bukan di APK.
exec bash "$(dirname "$0")/setup_server.sh" "$@"
