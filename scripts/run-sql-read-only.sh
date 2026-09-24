#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."
pw="$(grep '^APP_ATFM_DATASOURCE_PASSWORD=' .env | cut -d= -f2-)"
sqlplus.exe -L -S "atfm/\"$pw\"@172.29.187.90:1521/PDBORCL" "@$1"
