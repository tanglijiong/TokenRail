#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$repo_root/deploy/.runtime/besu"
m4_dir="$repo_root/deploy/.runtime/m4"
manifest="$runtime_dir/deployment-manifest.json"
foundry_bin="$repo_root/contracts/node_modules/.bin"

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

"$repo_root/scripts/besu-network.sh" up
"$repo_root/scripts/deploy-m3-contracts.sh"
mkdir -p "$m4_dir"

create_wallet() {
  local name="$1" wallet key address
  if [[ ! -f "$m4_dir/$name.key" ]]; then
    wallet="$(cast wallet new --json)"
    key="$(printf '%s' "$wallet" | jq -r '.[0].private_key')"
    printf '%s\n' "$key" > "$m4_dir/$name.key"
    chmod 600 "$m4_dir/$name.key"
  fi
  key="$(<"$m4_dir/$name.key")"
  address="$(cast wallet address --private-key "$key" | tr '[:upper:]' '[:lower:]')"
  printf '%s' "$address"
}

investor_a="$(create_wallet investor-a)"
investor_b="$(create_wallet investor-b)"
accounts_json="$(jq -cn --arg a "$investor_a" --arg b "$investor_b" '[$a,$b]')"

for port in 8545 8546 8547 8548; do
  current_accounts="$(cast rpc --rpc-url "http://127.0.0.1:$port" perm_getAccountsAllowlist)"
  missing_accounts="$(jq -cn --argjson wanted "$accounts_json" --argjson current "$current_accounts" \
    '$wanted | map(ascii_downcase) - ($current | map(ascii_downcase))')"
  if [[ "$(jq 'length' <<<"$missing_accounts")" -gt 0 ]]; then
    cast rpc --rpc-url "http://127.0.0.1:$port" \
      perm_addAccountsToAllowlist "$missing_accounts" >/dev/null
  fi
done

deployer_key="$(<"$runtime_dir/deployer.key")"
for address in "$investor_a" "$investor_b"; do
  cast send --legacy --gas-price 0 --private-key "$deployer_key" \
    --rpc-url http://127.0.0.1:8545 "$address" --value 1ether >/dev/null
done
unset deployer_key

registry_address="$(jq -r '.contracts.IdentityRegistry.address' "$manifest")"
deployer_key="$(<"$runtime_dir/deployer.key")"
valid_until=4102444800
for identity in "INVESTOR_A:$investor_a" "INVESTOR_B:$investor_b"; do
  participant_ref="${identity%%:*}"
  address="${identity#*:}"
  ref="$(cast format-bytes32-string "$participant_ref")"
  cast send --legacy --gas-price 0 --private-key "$deployer_key" \
    --rpc-url http://127.0.0.1:8545 "$registry_address" \
    'setIdentity(address,bytes32,uint64,bool,bool,bool,bool)' \
    "$address" "$ref" "$valid_until" true false true true >/dev/null
done
unset deployer_key

docker compose --profile app --env-file "$runtime_dir/compose.env" \
  -f "$repo_root/deploy/compose.yaml" up -d postgres
postgres_ready=false
for _ in {1..30}; do
  if docker compose --profile app --env-file "$runtime_dir/compose.env" \
      -f "$repo_root/deploy/compose.yaml" exec -T postgres \
      pg_isready -U tokenrail -d tokenrail >/dev/null 2>&1; then
    postgres_ready=true
    break
  fi
  sleep 1
done
if [[ "$postgres_ready" != true ]]; then
  docker compose --profile app --env-file "$runtime_dir/compose.env" \
    -f "$repo_root/deploy/compose.yaml" logs --tail=80 postgres
  printf 'PostgreSQL did not become ready.\n' >&2
  exit 1
fi

cat > "$m4_dir/server.env" <<EOF
TOKENRAIL_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/tokenrail
TOKENRAIL_DATABASE_USER=tokenrail
TOKENRAIL_DATABASE_PASSWORD=tokenrail-local-only
TOKENRAIL_CHAIN_ID=$(jq -r '.chainId' "$manifest")
TOKENRAIL_RPC_URL=http://127.0.0.1:8545
TOKENRAIL_DEPLOYMENT_ID=$(jq -r '.deploymentId' "$manifest")
TOKENRAIL_GENESIS_HASH=$(jq -r '.genesisHash' "$manifest")
TOKENRAIL_DEPOSIT_TOKEN_ADDRESS=$(jq -r '.contracts.DepositToken.address' "$manifest")
TOKENRAIL_IDENTITY_REGISTRY_ADDRESS=$(jq -r '.contracts.IdentityRegistry.address' "$manifest")
TOKENRAIL_BOND_TOKEN_ADDRESS=$(jq -r '.contracts.BondToken.address' "$manifest")
TOKENRAIL_DVP_ADDRESS=$(jq -r '.contracts.DvPSettlement.address' "$manifest")
TOKENRAIL_ESCROW_ADDRESS=$(jq -r '.contracts.PaymentEscrow.address' "$manifest")
TOKENRAIL_DEPLOYMENT_BLOCK=$(jq -r '.deploymentBlock' "$manifest")
TOKENRAIL_ISSUER_KEY_FILE=$runtime_dir/deployer.key
TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS=$investor_b
TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE=$m4_dir/investor-b.key
TOKENRAIL_PARTICIPANT_INVESTOR_B_OPENING_DEPOSIT=1000000000
TOKENRAIL_PARTICIPANT_INVESTOR_A_ADDRESS=$investor_a
TOKENRAIL_PARTICIPANT_INVESTOR_A_KEY_FILE=$m4_dir/investor-a.key
TOKENRAIL_PARTICIPANT_INVESTOR_A_OPENING_DEPOSIT=0
TOKENRAIL_PARTICIPANT_OPERATOR_ADDRESS=$(jq -r '.deployer' "$manifest")
TOKENRAIL_PARTICIPANT_OPERATOR_KEY_FILE=$runtime_dir/deployer.key
TOKENRAIL_PARTICIPANT_OPERATOR_OPENING_DEPOSIT=0
TOKENRAIL_PARTICIPANT_OPERATOR_ROLES=ISSUER,OPERATOR,COMPLIANCE,ATTESTOR,RECONCILER
TOKENRAIL_INVESTOR_A_ADDRESS=$investor_a
TOKENRAIL_INVESTOR_A_KEY_FILE=$m4_dir/investor-a.key
EOF
chmod 600 "$m4_dir/server.env"

printf 'M4 environment ready. Load %s before starting or testing the server.\n' "$m4_dir/server.env"
