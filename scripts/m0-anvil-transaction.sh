#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
foundry_bin="$repo_root/contracts/node_modules/.bin"
rpc_url="http://127.0.0.1:8545"
chain_id="31337"
anvil_log="$(mktemp -t tokenrail-anvil.XXXXXX)"

# Keep local JSON-RPC traffic away from host-level proxy settings.
export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"

recipient="0x000000000000000000000000000000000000b0b0"

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

wallet_json="$(cast wallet new --json)"
sender_key="$(printf '%s' "$wallet_json" | jq -r '.[0].private_key')"
sender="$(printf '%s' "$wallet_json" | jq -r '.[0].address' | tr '[:upper:]' '[:lower:]')"
unset wallet_json

anvil --silent --chain-id "$chain_id" >"$anvil_log" 2>&1 &
anvil_pid=$!

for _ in {1..40}; do
  if cast chain-id --rpc-url "$rpc_url" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done

actual_chain_id="$(cast chain-id --rpc-url "$rpc_url")"
[[ "$actual_chain_id" == "$chain_id" ]] || {
  printf 'Anvil did not become ready. Log: %s\n' "$anvil_log" >&2
  exit 1
}

# The key is generated for this process only; funding happens through Anvil's local test RPC.
cast rpc --rpc-url "$rpc_url" anvil_setBalance "$sender" \
  '0x56bc75e2d63100000' >/dev/null

derived_sender="$(cast wallet address --private-key "$sender_key")"
normalised_sender="$(printf '%s' "$derived_sender" | tr '[:upper:]' '[:lower:]')"
[[ "$normalised_sender" == "$sender" ]] || {
  printf 'Derived address does not match the expected local test account.\n' >&2
  exit 1
}

nonce_before="$(cast nonce "$sender" --rpc-url "$rpc_url")"
recipient_before="$(cast balance "$recipient" --rpc-url "$rpc_url")"

transaction_hash="$(cast send \
  --async \
  --legacy \
  --private-key "$sender_key" \
  --rpc-url "$rpc_url" \
  --value 1wei \
  "$recipient")"

receipt_json="$(cast receipt --json --confirmations 1 --rpc-url "$rpc_url" "$transaction_hash")"
status="$(printf '%s' "$receipt_json" | jq -r '.status')"
block_number="$(printf '%s' "$receipt_json" | jq -r '.blockNumber')"
gas_used="$(printf '%s' "$receipt_json" | jq -r '.gasUsed')"
nonce_after="$(cast nonce "$sender" --rpc-url "$rpc_url")"
recipient_after="$(cast balance "$recipient" --rpc-url "$rpc_url")"

[[ "$status" == "0x1" ]] || { printf 'Transaction failed: %s\n' "$transaction_hash" >&2; exit 1; }
[[ "$nonce_after" -eq $((nonce_before + 1)) ]] || { printf 'Sender nonce did not advance once.\n' >&2; exit 1; }
[[ "$recipient_after" -eq $((recipient_before + 1)) ]] || { printf 'Recipient balance did not increase by 1 wei.\n' >&2; exit 1; }

deployment_json="$(
  cd "$repo_root/contracts"
  forge create \
    --broadcast \
    --json \
    --legacy \
    --private-key "$sender_key" \
    --rpc-url "$rpc_url" \
    src/lab/StateTransitionLab.sol:StateTransitionLab \
    --constructor-args 10 100
)"
contract_address="$(printf '%s' "$deployment_json" | jq -r '.deployedTo')"
[[ "$contract_address" != "null" && -n "$contract_address" ]] || {
  printf 'StateTransitionLab deployment did not return an address.\n' >&2
  exit 1
}

counter_before="$(cast call --rpc-url "$rpc_url" "$contract_address" 'counter()(uint256)')"
success_hash="$(cast send \
  --async \
  --legacy \
  --private-key "$sender_key" \
  --rpc-url "$rpc_url" \
  "$contract_address" \
  'increaseBy(uint256)' 15)"
success_receipt="$(cast receipt --json --confirmations 1 --rpc-url "$rpc_url" "$success_hash")"
success_status="$(printf '%s' "$success_receipt" | jq -r '.status')"
counter_after_success="$(cast call --rpc-url "$rpc_url" "$contract_address" 'counter()(uint256)')"

nonce_before_revert="$(cast nonce "$sender" --rpc-url "$rpc_url")"
revert_hash="$(cast send \
  --async \
  --legacy \
  --gas-limit 100000 \
  --private-key "$sender_key" \
  --rpc-url "$rpc_url" \
  "$contract_address" \
  'increaseThenRevert(uint256)' 20)"
revert_receipt="$(cast receipt --json --confirmations 1 --rpc-url "$rpc_url" "$revert_hash")"
revert_status="$(printf '%s' "$revert_receipt" | jq -r '.status')"
revert_gas_used="$(printf '%s' "$revert_receipt" | jq -r '.gasUsed')"
nonce_after_revert="$(cast nonce "$sender" --rpc-url "$rpc_url")"
counter_after_revert="$(cast call --rpc-url "$rpc_url" "$contract_address" 'counter()(uint256)')"

[[ "$counter_before" -eq 10 ]] || { printf 'Unexpected initial counter.\n' >&2; exit 1; }
[[ "$success_status" == "0x1" && "$counter_after_success" -eq 25 ]] || {
  printf 'Successful contract call was not committed.\n' >&2
  exit 1
}
[[ "$revert_status" == "0x0" && "$counter_after_revert" -eq 25 ]] || {
  printf 'Reverted contract call changed state or returned an unexpected status.\n' >&2
  exit 1
}
[[ "$nonce_after_revert" -eq $((nonce_before_revert + 1)) ]] || {
  printf 'Reverted transaction did not consume exactly one nonce.\n' >&2
  exit 1
}

printf 'M0 signed transaction verified\n'
printf '  chainId:        %s\n' "$actual_chain_id"
printf '  signer address: %s\n' "$derived_sender"
printf '  transaction:    %s\n' "$transaction_hash"
printf '  blockNumber:    %s\n' "$block_number"
printf '  gasUsed:        %s\n' "$gas_used"
printf '  status:         %s\n' "$status"
printf '  nonce:          %s -> %s\n' "$nonce_before" "$nonce_after"
printf '  recipient wei:  %s -> %s\n' "$recipient_before" "$recipient_after"
printf 'M0 contract state transition verified\n'
printf '  contract:        %s\n' "$contract_address"
printf '  success tx:      %s (status %s)\n' "$success_hash" "$success_status"
printf '  counter:         %s -> %s\n' "$counter_before" "$counter_after_success"
printf '  reverted tx:     %s (status %s)\n' "$revert_hash" "$revert_status"
printf '  revert gasUsed:  %s\n' "$revert_gas_used"
printf '  revert nonce:    %s -> %s\n' "$nonce_before_revert" "$nonce_after_revert"
printf '  counter after revert: %s\n' "$counter_after_revert"
printf 'No private key is written to %s or printed above.\n' "$repo_root"
