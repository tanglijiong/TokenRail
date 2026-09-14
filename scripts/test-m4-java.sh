#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/deploy/.runtime/m4/server.env"

"$repo_root/scripts/m4-environment.sh"
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

TOKENRAIL_WORKER_ENABLED=false mvn -f "$repo_root/apps/server/pom.xml" verify
