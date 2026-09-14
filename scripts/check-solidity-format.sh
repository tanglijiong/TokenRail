#!/usr/bin/env bash
set -euo pipefail

format_output=""
if ! format_output="$(forge fmt --check 2>&1)"; then
  printf '%s\n' "$format_output" >&2
  exit 1
fi

# Foundry 1.7.1 reports formatting differences on stdout but exits successfully.
if [[ -n "$format_output" ]]; then
  printf '%s\n' "$format_output" >&2
  exit 1
fi
