#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$repo_root/deploy/.runtime/besu"
compose_file="$repo_root/deploy/compose.yaml"
template_file="$repo_root/deploy/besu/qbft-config.template.json"
besu_image="hyperledger/besu:26.8.1@sha256:6f3f21ce533383fcc8db3bce02252b59d5a9e776b72b5a1c8ecd2db011600042"
foundry_bin="$repo_root/contracts/node_modules/.bin"

export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="127.0.0.1,localhost${no_proxy:+,$no_proxy}"

if [[ -d "$foundry_bin" ]]; then
  PATH="$foundry_bin:$PATH"
fi

require_tools() {
  for command_name in docker jq cast; do
    command -v "$command_name" >/dev/null 2>&1 || {
      printf '%s is required.\n' "$command_name" >&2
      exit 1
    }
  done
  docker info >/dev/null 2>&1 || {
    printf 'Docker is installed but its daemon is not running.\n' >&2
    exit 1
  }
}

compose() {
  docker compose --env-file "$runtime_dir/compose.env" -f "$compose_file" "$@"
}

prepare() {
  require_tools
  if [[ -f "$runtime_dir/genesis.json" ]]; then
    printf 'Besu runtime already prepared at %s\n' "$runtime_dir"
    return
  fi

  mkdir -p "$runtime_dir"
  find "$runtime_dir" -mindepth 1 -delete
  mkdir -p "$runtime_dir/generated"
  wallet_json="$(cast wallet new --json)"
  deployer_key="$(printf '%s' "$wallet_json" | jq -r '.[0].private_key')"
  deployer_address="$(printf '%s' "$wallet_json" | jq -r '.[0].address' | tr '[:upper:]' '[:lower:]')"
  unset wallet_json

  printf '%s\n' "$deployer_key" > "$runtime_dir/deployer.key"
  chmod 600 "$runtime_dir/deployer.key"
  jq --arg account "${deployer_address#0x}" \
    '.genesis.alloc[$account] = {"balance":"0x3635c9adc5dea00000"}' \
    "$template_file" > "$runtime_dir/operator-config.json"

  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$runtime_dir:/work" \
    "$besu_image" \
    operator generate-blockchain-config \
    --config-file=/work/operator-config.json \
    --to=/work/generated >/dev/null

  cp "$runtime_dir/generated/genesis.json" "$runtime_dir/genesis.json"
  mapfile_command=(find "$runtime_dir/generated/keys" -mindepth 1 -maxdepth 1 -type d -print)
  if [[ "$(uname -s)" == "Darwin" ]]; then
    node_key_dirs=()
    while IFS= read -r key_dir; do node_key_dirs+=("$key_dir"); done < <("${mapfile_command[@]}" | sort)
  else
    mapfile -t node_key_dirs < <("${mapfile_command[@]}" | sort)
  fi
  [[ "${#node_key_dirs[@]}" -eq 4 ]] || {
    printf 'Expected four generated validator key directories.\n' >&2
    exit 1
  }

  node_urls=()
  validator_addresses=()
  for index in 1 2 3 4; do
    key_dir="${node_key_dirs[$((index - 1))]}"
    node_dir="$runtime_dir/node$index"
    mkdir -p "$node_dir/data"
    cp "$key_dir/key.priv" "$node_dir/key.priv"
    cp "$key_dir/key.pub" "$node_dir/key.pub"
    chmod 600 "$node_dir/key.priv"
    public_key="$(tr -d '\r\n' < "$node_dir/key.pub")"
    public_key="${public_key#0x}"
    validator_address="$(basename "$key_dir" | tr '[:upper:]' '[:lower:]')"
    validator_addresses+=("$validator_address")
    node_urls+=("enode://${public_key}@10.42.0.$((10 + index)):30303")
  done

  {
    printf 'accounts-allowlist=["%s"]\n' "$deployer_address"
    printf 'nodes-allowlist=['
    for index in 0 1 2 3; do
      [[ "$index" -eq 0 ]] || printf ','
      printf '"%s"' "${node_urls[$index]}"
    done
    printf ']\n'
  } > "$runtime_dir/permissions_config.toml"

  printf '%s\n' "${node_urls[@]}" | jq -R . | jq -s . > "$runtime_dir/static-nodes.json"
  for index in 1 2 3 4; do
    cp "$runtime_dir/permissions_config.toml" "$runtime_dir/node$index/data/permissions_config.toml"
  done

  printf 'TOKENRAIL_BESU_RUNTIME_DIR=%s\n' "$runtime_dir" > "$runtime_dir/compose.env"

  jq -n \
    --arg image "$besu_image" \
    --arg deployer "$deployer_address" \
    --argjson validators "$(printf '%s\n' "${validator_addresses[@]}" | jq -R . | jq -s .)" \
    --argjson enodes "$(printf '%s\n' "${node_urls[@]}" | jq -R . | jq -s .)" \
    '{chainId:20260913,besuImage:$image,deployer:$deployer,validators:$validators,enodes:$enodes}' \
    > "$runtime_dir/network.json"

  unset deployer_key
  printf 'Prepared four-validator QBFT runtime at %s\n' "$runtime_dir"
}

wait_for_rpc() {
  for _ in {1..60}; do
    if [[ "$(cast chain-id --rpc-url http://127.0.0.1:8545 2>/dev/null || true)" == "20260913" ]]; then
      return
    fi
    sleep 1
  done
  compose logs --tail=80
  printf 'Besu RPC did not become ready.\n' >&2
  exit 1
}

case "${1:-}" in
  prepare)
    prepare
    ;;
  up)
    prepare
    compose up -d
    wait_for_rpc
    printf 'TokenRail Besu RPC ready at http://127.0.0.1:8545\n'
    ;;
  down)
    require_tools
    [[ -f "$runtime_dir/compose.env" ]] && compose down --remove-orphans
    ;;
  status)
    require_tools
    [[ -f "$runtime_dir/compose.env" ]] || {
      printf 'Besu runtime has not been prepared.\n' >&2
      exit 1
    }
    compose ps
    ;;
  reset)
    require_tools
    if [[ -f "$runtime_dir/compose.env" ]]; then
      compose down --volumes --remove-orphans
    fi
    if [[ -d "$runtime_dir" ]]; then
      find "$runtime_dir" -mindepth 1 -delete
    fi
    printf 'Removed generated Besu runtime.\n'
    ;;
  *)
    printf 'Usage: %s {prepare|up|down|status|reset}\n' "$0" >&2
    exit 2
    ;;
esac
