#!/usr/bin/env bash
set -euo pipefail

test_output=""
test_status=0
test_output="$(forge test -vvv 2>&1)" || test_status=$?
printf '%s\n' "$test_output"

if [[ "$test_status" -ne 0 ]]; then
  exit "$test_status"
fi

# Foundry 1.7.1 can report some discovery failures without a non-zero process status.
if printf '%s\n' "$test_output" +  | grep -Eq '^\[FAIL|Suite result: FAILED|Encountered a total of [1-9][0-9]* failing tests'; then
  exit 1
fi
