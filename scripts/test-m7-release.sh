#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
evidence_dir="$repo_root/docs/evidence/m7-release-20260914"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/tokenrail-m7-clean.XXXXXX")"
log="$evidence_dir/clean-release.log"
keep="${TOKENRAIL_KEEP_M7_WORKDIR:-false}"

mkdir -p "$evidence_dir"
cleanup() {
  if [[ -x "$work_dir/scripts/besu-network.sh" ]]; then
    "$work_dir/scripts/besu-network.sh" down >/dev/null 2>&1 || true
  fi
  if [[ "$keep" != true ]]; then rm -rf "$work_dir"; fi
}
trap cleanup EXIT

for command_name in git node npm java mvn docker jq curl tar; do
  command -v "$command_name" >/dev/null 2>&1 || { printf '%s is required.\n' "$command_name" >&2; exit 1; }
done

if [[ -n "$(git -C "$repo_root" status --porcelain)" ]]; then
  printf 'M7 clean release requires a committed source tree.\n' >&2
  git -C "$repo_root" status --short >&2
  exit 1
fi

# The interactive demo intentionally leaves the shared Compose project running.
# Stop that known local environment before M0 claims port 8545 for temporary Anvil.
if [[ -f "$repo_root/deploy/.runtime/besu/compose.env" ]]; then
  "$repo_root/scripts/besu-network.sh" down >/dev/null
fi

git -C "$repo_root" archive --format=tar HEAD | tar -xf - -C "$work_dir"
if find "$work_dir" -type f \( -name '*.key' -o -name '*.keystore' -o -name '.env' -o -name 'server.env' \) | grep -q .; then
  printf 'Release archive contains generated credentials or environment files.\n' >&2
  exit 1
fi

exec > >(tee "$log") 2>&1
printf 'TokenRail M7 clean release acceptance\n'
printf 'commit=%s\n' "$(git -C "$repo_root" rev-parse HEAD)"
printf 'started=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'work_dir=%s\n' "$work_dir"
node --version
npm --version
java -version
mvn --version
docker --version

cd "$work_dir/contracts"
npm ci
npm run check

cd "$work_dir"
scripts/check-m0-environment.sh
scripts/m0-anvil-transaction.sh
scripts/m2-anvil-atomicity.sh
scripts/test-m3-besu.sh
scripts/test-m5-projection.sh
scripts/test-m51-reliability.sh
scripts/test-m6-fullstack.sh

test -f LICENSE
grep -q 'Apache License' LICENSE
test -f RELEASE_NOTES.md
grep -q 'TokenRail v0.1.0' RELEASE_NOTES.md

cp deploy/.runtime/besu/deployment-manifest.json "$evidence_dir/clean-deployment-manifest.json"
cp deploy/.runtime/m6/verify.log "$evidence_dir/clean-m6-verify.log"
printf 'completed=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'M7_CLEAN_RELEASE=PASS\n'
