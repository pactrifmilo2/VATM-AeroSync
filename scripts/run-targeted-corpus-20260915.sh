#!/usr/bin/env bash
# Runs the targeted Word-permit corpus regression over the 7 repair-scope files
# with live ATFM resolution, writing parsed-document reports.
set -u
cd "$(dirname "$0")/.."
ROOT="$PWD"
MANIFEST="$ROOT/reports/targeted-repair-run-2026-09-15/manifest.txt"
PARSED="$ROOT/reports/targeted-repair-run-2026-09-15/parsed"
LOG="$ROOT/reports/targeted-repair-run-2026-09-15/test-output.log"

export APP_ATFM_DATASOURCE_URL="jdbc:oracle:thin:@//172.29.187.90:1521/PDBORCL"
export APP_ATFM_DATASOURCE_USERNAME="atfm"
export APP_ATFM_DATASOURCE_PASSWORD="$(grep '^APP_ATFM_DATASOURCE_PASSWORD=' "$ROOT/.env" | cut -d= -f2-)"

mkdir -p "$PARSED"
./aerosync-worker/mvnw -f pom.xml test -pl aerosync-worker -am \
  -Dtest=WordPermitCorpusRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpermit.corpus.manifest="$(cygpath -w "$MANIFEST")" \
  -Dpermit.corpus.resolve-aircraft=true \
  -Dpermit.corpus.report.dir="$(cygpath -w "$PARSED")" \
  > "$LOG" 2>&1
echo "EXIT=$?"
