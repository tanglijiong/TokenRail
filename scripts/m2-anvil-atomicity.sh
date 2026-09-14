#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
foundry_bin="$repo_root/contracts/node_modules/.bin"
rpc_url="http://127.0.0.1:8545"
anvil_log="$(mktemp -t tokenrail-m2-anvil.XXXXXX)"

export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"

cleanup() {
  if [[ -n "${anvil_pid:-}" ]]; then
    kill "$anvil_pid" 2>/dev/null || true
    wait "$anvil_pid" 2>/dev/null || true
  fi
  rm -f "$anvil_log"
}
trap cleanup EXIT

if [[ -d "$foundry_bin" ]]; then
  PATH="$foundry_bin:$PATH"
fi

for command_name in forge cast anvil jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf '%s is required. Run scripts/check-m0-environment.sh first.\n' "$command_name" >&2
    exit 1
  }
done

uint_call() {
  cast call --rpc-url "$rpc_url" "$1" "$2" | awk '{print $1}'
}

wallet_json="$(cast wallet new --json)"
deployer_key="$(printf '%s' "$wallet_json" | jq -r '.[0].private_key')"
deployer="$(printf '%s' "$wallet_json" | jq -r '.[0].address')"
unset wallet_json

anvil --silent --chain-id 31337 >"$anvil_log" 2>&1 &
anvil_pid=$!
for _ in {1..40}; do
  if cast chain-id --rpc-url "$rpc_url" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
[[ "$(cast chain-id --rpc-url "$rpc_url")" == "31337" ]] || {
  printf 'Anvil did not become ready.\n' >&2
  exit 1
}

cast rpc --rpc-url "$rpc_url" anvil_setBalance "$deployer" \
  '0x56bc75e2d63100000' >/dev/null

deployment_json="$(
  cd "$repo_root/contracts"
  forge create \
    --broadcast \
    --json \
    --legacy \
    --gas-limit 25000000 \
    --private-key "$deployer_key" \
    --rpc-url "$rpc_url" \
    test/integration/M2AtomicityReceiptHarness.sol:M2AtomicityReceiptHarness
)"
harness="$(printf '%s' "$deployment_json" | jq -r '.deployedTo')"
[[ "$harness" != "null" && -n "$harness" ]] || {
  printf 'M2 receipt harness deployment failed.\n' >&2
  exit 1
}

seller_before="$(uint_call "$harness" 'sellerBondBalance()(uint256)')"
buyer_before="$(uint_call "$harness" 'buyerBondBalance()(uint256)')"
allowance_before="$(uint_call "$harness" 'sellerBondAllowance()(uint256)')"
cash_before="$(uint_call "$harness" 'buyerCashBalance()(uint256)')"
state_before="$(uint_call "$harness" 'instructionState()(uint8)')"

failure_hash="$(cast send \
  --async \
  --legacy \
  --gas-limit 500000 \
  --private-key "$deployer_key" \
  --rpc-url "$rpc_url" \
  "$harness" \
  'executeFailure()')"
receipt="$(cast receipt --json --confirmations 1 --rpc-url "$rpc_url" "$failure_hash")"
status="$(printf '%s' "$receipt" | jq -r '.status')"
log_count="$(printf '%s' "$receipt" | jq '.logs | length')"
gas_used="$(printf '%s' "$receipt" | jq -r '.gasUsed')"

seller_after="$(uint_call "$harness" 'sellerBondBalance()(uint256)')"
buyer_after="$(uint_call "$harness" 'buyerBondBalance()(uint256)')"
allowance_after="$(uint_call "$harness" 'sellerBondAllowance()(uint256)')"
cash_after="$(uint_call "$harness" 'buyerCashBalance()(uint256)')"
state_after="$(uint_call "$harness" 'instructionState()(uint8)')"

[[ "$status" == "0x0" && "$log_count" -eq 0 ]] || {
  printf 'Failed transaction produced unexpected receipt status or logs.\n' >&2
  exit 1
}
[[ "$seller_before" -eq 1000 && "$seller_after" -eq "$seller_before" ]] || exit 1
[[ "$buyer_before" -eq 0 && "$buyer_after" -eq "$buyer_before" ]] || exit 1
[[ "$allowance_before" -eq 1000 && "$allowance_after" -eq "$allowance_before" ]] || exit 1
[[ "$cash_before" -eq 100000000 && "$cash_after" -eq "$cash_before" ]] || exit 1
[[ "$state_before" -eq 2 && "$state_after" -eq "$state_before" ]] || exit 1

printf 'M2 second-leg failure receipt verified\n'
printf '  transaction:      %s\n' "$failure_hash"
printf '  status:           %s\n' "$status"
printf '  receipt logs:     %s\n' "$log_count"
printf '  gasUsed:          %s\n' "$gas_used"
printf '  seller bond:      %s -> %s\n' "$seller_before" "$seller_after"
printf '  buyer bond:       %s -> %s\n' "$buyer_before" "$buyer_after"
printf '  bond allowance:   %s -> %s\n' "$allowance_before" "$allowance_after"
printf '  buyer cash:       %s -> %s\n' "$cash_before" "$cash_after"
printf '  instruction state:%s -> %s (READY)\n' "$state_before" "$state_after"
printf 'The ephemeral deployer key was not written to disk or logs.\n'
