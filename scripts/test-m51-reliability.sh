#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$repo_root/contracts/node_modules/.bin:$PATH"
export NO_PROXY="127.0.0.1,localhost" no_proxy="127.0.0.1,localhost"
mkdir -p "$repo_root/deploy/.runtime/m51"
run_dir="$(mktemp -d "$repo_root/deploy/.runtime/m51/run.XXXXXX")"
port="${TOKENRAIL_M51_PORT:-18545}"
database="m51_$(date +%s)_$$"
postgres="${TOKENRAIL_TEST_POSTGRES_CONTAINER:-tokenrail-m3-postgres-1}"
anvil --port "$port" --chain-id 20260913 --base-fee 0 --gas-price 0 --silent > "$run_dir/anvil.log" 2>&1 &
anvil_pid=$!
trap 'kill "$anvil_pid" 2>/dev/null || true' EXIT
export TOKENRAIL_RPC_URL="http://127.0.0.1:$port"
for _ in {1..30}; do
  if cast chain-id --rpc-url "$TOKENRAIL_RPC_URL" >/dev/null 2>&1; then break; fi
  sleep 1
done
kill -0 "$anvil_pid" # Do not accidentally test against a pre-existing node on this port.
docker exec "$postgres" createdb -U tokenrail "$database"
export TOKENRAIL_DEPLOY_RUNTIME_DIR="$run_dir"
wallet() {
  cast wallet new --json | jq -r '.[0].private_key' > "$run_dir/$1.key"
  chmod 600 "$run_dir/$1.key"
  local address
  address="$(cast wallet address --private-key "$(<"$run_dir/$1.key")")"
  cast rpc --rpc-url "$TOKENRAIL_RPC_URL" anvil_setBalance "$address" 0x3635c9adc5dea00000 >/dev/null
  printf '%s' "$address"
}
issuer="$(wallet deployer)"
investor_a="$(wallet investor-a)"
investor_b="$(wallet investor-b)"
"$repo_root/scripts/deploy-m3-contracts.sh" > "$run_dir/deploy.log" 2>&1
manifest="$run_dir/deployment-manifest.json"
export TOKENRAIL_DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$database"
export TOKENRAIL_DATABASE_USER=tokenrail TOKENRAIL_DATABASE_PASSWORD=tokenrail-local-only
export TOKENRAIL_CHAIN_ID=20260913
export TOKENRAIL_DEPLOYMENT_ID="$(jq -r .deploymentId "$manifest")"
export TOKENRAIL_GENESIS_HASH="$(jq -r .genesisHash "$manifest")"
export TOKENRAIL_DEPOSIT_TOKEN_ADDRESS="$(jq -r .contracts.DepositToken.address "$manifest")"
export TOKENRAIL_BOND_TOKEN_ADDRESS="$(jq -r .contracts.BondToken.address "$manifest")"
export TOKENRAIL_IDENTITY_REGISTRY_ADDRESS="$(jq -r .contracts.IdentityRegistry.address "$manifest")"
export TOKENRAIL_DVP_ADDRESS="$(jq -r .contracts.DvPSettlement.address "$manifest")"
export TOKENRAIL_ESCROW_ADDRESS="$(jq -r .contracts.PaymentEscrow.address "$manifest")"
export TOKENRAIL_DEPLOYMENT_BLOCK="$(jq -r .deploymentBlock "$manifest")"
export TOKENRAIL_ISSUER_KEY_FILE="$run_dir/deployer.key"
export TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS="$investor_b"
export TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE="$run_dir/investor-b.key"
export TOKENRAIL_INVESTOR_A_ADDRESS="$investor_a"
export TOKENRAIL_INVESTOR_A_KEY_FILE="$run_dir/investor-a.key"
export TOKENRAIL_PARTICIPANT_OPERATOR_ADDRESS="$issuer"
export TOKENRAIL_PARTICIPANT_OPERATOR_KEY_FILE="$run_dir/deployer.key"
export TOKENRAIL_PARTICIPANT_OPERATOR_ROLES=ISSUER,OPERATOR,COMPLIANCE,ATTESTOR,RECONCILER
export TOKENRAIL_M51_RUN_DIR="$run_dir"
export TOKENRAIL_WORKER_ENABLED=false TOKENRAIL_INDEXER_ENABLED=false
printf 'Reliability evidence: %s (database %s)\n' "$run_dir" "$database"
mvn -f "$repo_root/apps/server/pom.xml" -Dit.test=M51ReliabilityIT verify 2>&1 | tee "$run_dir/verify.log"
