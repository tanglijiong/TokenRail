#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/deploy/.runtime/m4/server.env"

"$repo_root/scripts/m4-environment.sh"
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

mkdir -p "$repo_root/deploy/.runtime/m6"
TOKENRAIL_WORKER_ENABLED=false TOKENRAIL_INDEXER_ENABLED=false \
  mvn -f "$repo_root/apps/server/pom.xml" -Dit.test=M6BusinessIT verify 2>&1 \
  | tee "$repo_root/deploy/.runtime/m6/verify.log"
