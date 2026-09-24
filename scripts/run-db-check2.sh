#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/.."
bash scripts/run-sql-read-only.sh reports/targeted-repair-run-2026-09-15/db-check2.sql
