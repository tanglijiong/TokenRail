#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
foundry_bin="$repo_root/contracts/node_modules/.bin"
required_foundry_version="1.7.1"

if [[ -d "$foundry_bin" ]]; then
  PATH="$foundry_bin:$PATH"
fi

print_version() {
  local command_name="$1"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf '%-8s MISSING\n' "$command_name"
    return 1
  fi

  printf '%-8s %s\n' "$command_name" "$("$command_name" --version 2>&1 | head -n 1)"
}

status=0
for command_name in forge cast anvil; do
  print_version "$command_name" || status=1
done

print_version java || status=1
print_version mvn || status=1
print_version docker || status=1

if command -v forge >/dev/null 2>&1; then
  installed_foundry_version="$(forge --version | sed -n 's/^forge Version: \([^ -]*\).*/\1/p')"
  if [[ "$installed_foundry_version" != "$required_foundry_version" ]]; then
    printf 'Foundry version mismatch: expected %s, found %s\n' \
      "$required_foundry_version" "${installed_foundry_version:-unknown}" >&2
    status=1
  fi
fi

exit "$status"
