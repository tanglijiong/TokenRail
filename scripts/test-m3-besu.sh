#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$repo_root/deploy/.runtime/besu"
compose_file="$repo_root/deploy/compose.yaml"
foundry_bin="$repo_root/contracts/node_modules/.bin"
rpc_url="http://127.0.0.1:8545"

export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"

if [[ -d "$foundry_bin" ]]; then
  PATH="$foundry_bin:$PATH"
fi

for command_name in docker jq cast; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf '%s is required.\n' "$command_name" >&2
    exit 1
  }
done

compose() {
  docker compose --env-file "$runtime_dir/compose.env" -f "$compose_file" "$@"
}

restore_validators() {
  if [[ -f "$runtime_dir/compose.env" ]]; then
    compose start node3 node4 >/dev/null 2>&1 || true
  fi
}
trap restore_validators EXIT

"$repo_root/scripts/besu-network.sh" reset
"$repo_root/scripts/besu-network.sh" up

for _ in {1..30}; do
  peer_count="$(cast rpc --rpc-url "$rpc_url" net_peerCount 2>/dev/null | jq -r '.' || true)"
  [[ "$peer_count" == "0x3" ]] && break
  sleep 1
done
[[ "$peer_count" == "0x3" ]] || {
  compose logs --tail=100
  printf 'Node 1 did not connect to the other three allowlisted nodes.\n' >&2
  exit 1
}

validators="$(cast rpc --rpc-url "$rpc_url" qbft_getValidatorsByBlockNumber latest | jq -r 'length')"
allowed_nodes="$(cast rpc --rpc-url "$rpc_url" perm_getNodesAllowlist | jq -r 'length')"
allowed_accounts="$(cast rpc --rpc-url "$rpc_url" perm_getAccountsAllowlist | jq -r 'length')"
[[ "$validators" -eq 4 && "$allowed_nodes" -eq 4 && "$allowed_accounts" -eq 1 ]] || {
  printf 'Validator or permissioning configuration does not match the four-node design.\n' >&2
  exit 1
}

outsider_wallet="$(cast wallet new --json)"
outsider_key="$(printf '%s' "$outsider_wallet" | jq -r '.[0].private_key')"
outsider_address="$(printf '%s' "$outsider_wallet" | jq -r '.[0].address')"
permission_result="$(cast send --legacy --gas-price 0 --gas-limit 21000 \
  --private-key "$outsider_key" --rpc-url "$rpc_url" "$outsider_address" --value 0 2>&1 || true)"
unset outsider_wallet outsider_key
printf '%s' "$permission_result" | rg -q 'Sender account not authorized' || {
  printf 'Unlisted transaction sender was not rejected by account permissioning.\n' >&2
  exit 1
}

head_before="$(cast block-number --rpc-url "$rpc_url")"
sleep 5
head_after="$(cast block-number --rpc-url "$rpc_url")"
[[ "$head_after" -gt "$head_before" ]] || {
  printf 'Four-validator network did not produce blocks.\n' >&2
  exit 1
}

"$repo_root/scripts/deploy-m3-contracts.sh"
manifest="$runtime_dir/deployment-manifest.json"
for name in IdentityRegistry DepositToken BondToken DvPSettlement; do
  address="$(jq -r ".contracts.$name.address" "$manifest")"
  [[ "$(cast code --rpc-url "$rpc_url" "$address")" != "0x" ]] || {
    printf '%s has no deployed bytecode.\n' "$name" >&2
    exit 1
  }
done
deposit_address="$(jq -r '.contracts.DepositToken.address' "$manifest")"
[[ "$(cast call --rpc-url "$rpc_url" "$deposit_address" 'decimals()(uint8)' | awk '{print $1}')" == "2" ]] || exit 1

compose stop node4 >/dev/null
head_before="$(cast block-number --rpc-url "$rpc_url")"
sleep 7
head_after="$(cast block-number --rpc-url "$rpc_url")"
[[ "$head_after" -gt "$head_before" ]] || {
  printf 'Network stopped after only one validator was removed.\n' >&2
  exit 1
}

compose start node4 >/dev/null
sleep 5
compose stop node3 node4 >/dev/null
sleep 6
stalled_head="$(cast block-number --rpc-url "$rpc_url")"
sleep 8
stalled_head_after="$(cast block-number --rpc-url "$rpc_url")"
[[ "$stalled_head_after" -eq "$stalled_head" ]] || {
  printf 'Network continued producing blocks with only two of four validators.\n' >&2
  exit 1
}

compose start node3 node4 >/dev/null
# QBFT round timeouts double while quorum is unavailable. Restarting all local validators resets
# the lab network's round timeout after the deliberate stall.
compose restart node1 node2 node3 node4 >/dev/null
for _ in {1..60}; do
  recovered_head="$(cast block-number --rpc-url "$rpc_url")"
  [[ "$recovered_head" -gt "$stalled_head_after" ]] && break
  sleep 1
done
[[ "$recovered_head" -gt "$stalled_head_after" ]] || {
  printf 'Network did not recover after validators restarted.\n' >&2
  exit 1
}

printf 'M3 four-validator Besu verification passed\n'
printf '  chain ID:                    20260913\n'
printf '  validators:                  %s\n' "$validators"
printf '  node 1 peers:                %d\n' "$((peer_count))"
printf '  unlisted transaction sender: rejected\n'
printf '  one-validator-down blocks:   %s -> %s\n' "$head_before" "$head_after"
printf '  two-validator-down head:     %s -> %s (stalled)\n' "$stalled_head" "$stalled_head_after"
printf '  recovered head:              %s\n' "$recovered_head"
printf '  deployment manifest:         %s\n' "$manifest"
