#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="${TOKENRAIL_DEPLOY_RUNTIME_DIR:-$repo_root/deploy/.runtime/besu}"
rpc_url="${TOKENRAIL_RPC_URL:-http://127.0.0.1:8545}"
foundry_bin="$repo_root/contracts/node_modules/.bin"

export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"

if [[ -d "$foundry_bin" ]]; then
  PATH="$foundry_bin:$PATH"
fi

for command_name in forge cast jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf '%s is required.\n' "$command_name" >&2
    exit 1
  }
done
[[ -f "$runtime_dir/deployer.key" ]] || {
  printf 'Run scripts/besu-network.sh up first.\n' >&2
  exit 1
}
[[ "$(cast chain-id --rpc-url "$rpc_url")" == "20260913" ]] || {
  printf 'Unexpected or unavailable chain ID.\n' >&2
  exit 1
}

deployer_key="$(<"$runtime_dir/deployer.key")"
deployer_address="$(cast wallet address --private-key "$deployer_key" | tr '[:upper:]' '[:lower:]')"

deploy_contract() {
  local contract="$1"
  shift
  local deployment
  deployment="$(
    cd "$repo_root/contracts"
    forge create --broadcast --json --legacy --gas-price 0 \
      --private-key "$deployer_key" --rpc-url "$rpc_url" "$contract" "$@"
  )"
  printf '%s' "$deployment"
}

validate_deployment() {
  local name="$1" address="$2" tx_hash="$3"
  local status
  if [[ ! "$address" =~ ^0x[0-9a-fA-F]{40}$ || ! "$tx_hash" =~ ^0x[0-9a-fA-F]{64}$ ]]; then
    printf '%s deployment did not return a valid address and transaction hash.\n' "$name" >&2
    exit 1
  fi
  status="$(cast receipt --json --rpc-url "$rpc_url" "$tx_hash" | jq -r '.status')"
  [[ "$status" == "0x1" ]] || {
    printf '%s deployment receipt failed.\n' "$name" >&2
    exit 1
  }
}

registry_json="$(deploy_contract src/identity/IdentityRegistry.sol:IdentityRegistry --constructor-args "$deployer_address" "$deployer_address")"
registry_address="$(printf '%s' "$registry_json" | jq -r '.deployedTo')"
registry_tx="$(printf '%s' "$registry_json" | jq -r '.transactionHash')"
validate_deployment IdentityRegistry "$registry_address" "$registry_tx"

cash_json="$(deploy_contract src/token/DepositToken.sol:DepositToken --constructor-args "$registry_address" "$deployer_address" "$deployer_address")"
cash_address="$(printf '%s' "$cash_json" | jq -r '.deployedTo')"
cash_tx="$(printf '%s' "$cash_json" | jq -r '.transactionHash')"
validate_deployment DepositToken "$cash_address" "$cash_tx"

asset_ref="$(cast format-bytes32-string ABC2029)"
issuer_ref="$(cast format-bytes32-string TOKENRAIL_BANK)"
bond_json="$(deploy_contract src/token/BondToken.sol:BondToken --constructor-args \
  "$registry_address" "$deployer_address" "$deployer_address" \
  "$asset_ref" "$issuer_ref" 0x434e59 100000 320 1893456000 100000)"
bond_address="$(printf '%s' "$bond_json" | jq -r '.deployedTo')"
bond_tx="$(printf '%s' "$bond_json" | jq -r '.transactionHash')"
validate_deployment BondToken "$bond_address" "$bond_tx"

dvp_json="$(deploy_contract src/settlement/DvPSettlement.sol:DvPSettlement --constructor-args \
  "$cash_address" "$bond_address" "$registry_address" "$deployer_address" "$deployer_address")"
dvp_address="$(printf '%s' "$dvp_json" | jq -r '.deployedTo')"
dvp_tx="$(printf '%s' "$dvp_json" | jq -r '.transactionHash')"
validate_deployment DvPSettlement "$dvp_address" "$dvp_tx"

escrow_json="$(deploy_contract src/payment/PaymentEscrow.sol:PaymentEscrow --constructor-args \
  "$cash_address" "$registry_address" "$deployer_address" "$deployer_address")"
escrow_address="$(printf '%s' "$escrow_json" | jq -r '.deployedTo')"
escrow_tx="$(printf '%s' "$escrow_json" | jq -r '.transactionHash')"
validate_deployment PaymentEscrow "$escrow_address" "$escrow_tx"
cast send --legacy --gas-price 0 --private-key "$deployer_key" \
  --rpc-url "$rpc_url" "$registry_address" 'setSystemAccount(address,bool)' \
  "$escrow_address" true >/dev/null

genesis_hash="$(cast block 0 --json --rpc-url "$rpc_url" | jq -r '.hash')"
deployment_block="$(cast block-number --rpc-url "$rpc_url")"
deployment_nonce="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
deployment_id_source="$(cast from-utf8 "$genesis_hash:$dvp_tx:$deployment_nonce")"
deployment_id="$(cast keccak "$deployment_id_source")"

contract_manifest() {
  local name="$1" address="$2" tx_hash="$3" artifact="$4"
  local abi_hash abi_json bytecode_hash block_number
  abi_json="$(jq -cS '.abi' "$repo_root/contracts/out/$artifact")"
  abi_hash="$(cast keccak "$(cast from-utf8 "$abi_json")")"
  bytecode_hash="$(cast code --rpc-url "$rpc_url" "$address" | cast keccak)"
  block_number="$(cast receipt --json --rpc-url "$rpc_url" "$tx_hash" | jq -r '.blockNumber' | xargs printf '%d')"
  jq -n --arg name "$name" --arg address "$address" --arg txHash "$tx_hash" \
    --arg abiHash "$abi_hash" --arg bytecodeHash "$bytecode_hash" \
    --argjson blockNumber "$block_number" \
    '{name:$name,address:$address,transactionHash:$txHash,blockNumber:$blockNumber,abiHash:$abiHash,bytecodeHash:$bytecodeHash}'
}

[[ "$deployment_id" =~ ^0x[0-9a-fA-F]{64}$ ]] || {
  printf 'Deployment ID generation failed.\n' >&2
  exit 1
}

jq -n \
  --arg deploymentId "$deployment_id" \
  --arg genesisHash "$genesis_hash" \
  --arg deployer "$deployer_address" \
  --arg besuVersion "26.8.1" \
  --arg solidityVersion "0.8.30" \
  --arg evmVersion "cancun" \
  --argjson deploymentBlock "$deployment_block" \
  --argjson identityRegistry "$(contract_manifest IdentityRegistry "$registry_address" "$registry_tx" IdentityRegistry.sol/IdentityRegistry.json)" \
  --argjson depositToken "$(contract_manifest DepositToken "$cash_address" "$cash_tx" DepositToken.sol/DepositToken.json)" \
  --argjson bondToken "$(contract_manifest BondToken "$bond_address" "$bond_tx" BondToken.sol/BondToken.json)" \
  --argjson dvpSettlement "$(contract_manifest DvPSettlement "$dvp_address" "$dvp_tx" DvPSettlement.sol/DvPSettlement.json)" \
  --argjson paymentEscrow "$(contract_manifest PaymentEscrow "$escrow_address" "$escrow_tx" PaymentEscrow.sol/PaymentEscrow.json)" \
  '{deploymentId:$deploymentId,chainId:20260913,genesisHash:$genesisHash,deploymentBlock:$deploymentBlock,deployer:$deployer,tools:{besu:$besuVersion,solidity:$solidityVersion,evm:$evmVersion},contracts:{IdentityRegistry:$identityRegistry,DepositToken:$depositToken,BondToken:$bondToken,DvPSettlement:$dvpSettlement,PaymentEscrow:$paymentEscrow}}' \
  > "$runtime_dir/deployment-manifest.json"

unset deployer_key
printf 'M3 contracts deployed; manifest: %s\n' "$runtime_dir/deployment-manifest.json"
printf '  IdentityRegistry %s\n' "$registry_address"
printf '  DepositToken     %s\n' "$cash_address"
printf '  BondToken        %s\n' "$bond_address"
printf '  DvPSettlement    %s\n' "$dvp_address"
printf '  PaymentEscrow    %s\n' "$escrow_address"
