#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="$repo_root/contracts/node_modules/.bin:$PATH"
port="${TOKENRAIL_DEMO_PORT:-18081}"
base="http://127.0.0.1:$port/api/v1"
output_dir="$repo_root/output/demo"
operations_file="$(mktemp)"
server_pid=""
keep_running=false
[[ "${1:-}" == "--keep-running" ]] && keep_running=true

cleanup() {
  rm -f "$operations_file"
  if [[ -n "$server_pid" && "$keep_running" != true ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for command_name in curl jq cast java mvn; do
  command -v "$command_name" >/dev/null 2>&1 || { printf '%s is required.\n' "$command_name" >&2; exit 1; }
done
if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port %s is already in use. Stop the existing demo first.\n' "$port" >&2
  exit 1
fi

mkdir -p "$output_dir"
"$repo_root/scripts/m4-environment.sh"
set -a
# shellcheck disable=SC1091
source "$repo_root/deploy/.runtime/m4/server.env"
set +a

mvn -q -f "$repo_root/apps/server/pom.xml" -DskipTests package
TOKENRAIL_WORKER_ENABLED=true TOKENRAIL_INDEXER_ENABLED=true \
  java -jar "$repo_root/apps/server/target/tokenrail-server-0.0.1-SNAPSHOT.jar" \
  --server.port="$port" > "$output_dir/server.log" 2>&1 &
server_pid=$!
for _ in {1..60}; do
  curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && break
  kill -0 "$server_pid" 2>/dev/null || { tail -80 "$output_dir/server.log" >&2; exit 1; }
  sleep 1
done
curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null

api_get() {
  curl -fsS -H "Authorization: Bearer $1" "$base$2"
}

submit_wait() {
  local token="$1" path="$2" label="$3" body="${4:-}" expected="${5:-SUCCEEDED}"
  local key response code payload operation state
  key="m7-$label-$(date +%s%N)"
  if [[ -n "$body" ]]; then
    response="$(curl -sS -w $'\n%{http_code}' -X POST "$base$path" \
      -H "Authorization: Bearer $token" -H "Idempotency-Key: $key" \
      -H 'Content-Type: application/json' --data "$body")"
  else
    response="$(curl -sS -w $'\n%{http_code}' -X POST "$base$path" \
      -H "Authorization: Bearer $token" -H "Idempotency-Key: $key")"
  fi
  code="${response##*$'\n'}"; payload="${response%$'\n'*}"
  [[ "$code" == 202 ]] || { printf '%s returned HTTP %s: %s\n' "$label" "$code" "$payload" >&2; exit 1; }
  operation="$(jq -er '.operationId' <<<"$payload")"
  for _ in {1..120}; do
    payload="$(api_get "$token" "/operations/$operation")"
    state="$(jq -r '.status' <<<"$payload")"
    if [[ "$state" =~ ^(SUCCEEDED|COMPLETED|CREDITED|REVERTED)$ ]]; then break; fi
    sleep 0.5
  done
  [[ "$state" == "$expected" ]] || { printf '%s ended as %s, expected %s\n' "$label" "$state" "$expected" >&2; exit 1; }
  jq -c --arg label "$label" '. + {label:$label}' <<<"$payload" >> "$operations_file"
  printf '%s\n' "$operation"
}

wait_projected() {
  local token="$1" path="$2" payload=""
  for _ in {1..120}; do
    payload="$(api_get "$token" "$path")"
    [[ "$(jq -r '.syncStatus' <<<"$payload")" == PROJECTED ]] && { printf '%s' "$payload"; return; }
    sleep 0.5
  done
  printf 'Resource %s did not reach PROJECTED\n' "$path" >&2
  exit 1
}

id32() { cast keccak "$1-$(date +%s%N)-$RANDOM"; }
balance() { cast call --rpc-url "$TOKENRAIL_RPC_URL" "$1" 'balanceOf(address)(uint256)' "$2" | awk '{print $1}'; }
future_iso() { node -e 'console.log(new Date(Date.now()+Number(process.argv[1])*1000).toISOString())' "$1"; }

wallet_a="$TOKENRAIL_PARTICIPANT_INVESTOR_A_ADDRESS"
wallet_b="$TOKENRAIL_PARTICIPANT_INVESTOR_B_ADDRESS"
operator="$TOKENRAIL_PARTICIPANT_OPERATOR_ADDRESS"

# Shared synthetic funding.
submit_wait operator-token /bond-issues issue-bond "$(jq -cn --arg id "$(id32 issue)" --arg recipient "$wallet_a" \
  '{issueId:$id,recipient:$recipient,quantity:"30"}')" >/dev/null
submit_wait investor-b-token /deposit-conversions convert-cash "$(jq -cn --arg id "$(id32 conversion)" \
  '{businessId:$id,amount:"100000"}')" COMPLETED >/dev/null

# Scenario A: release before deadline, and a separate timeout refund.
submit_wait investor-b-token /allowances escrow-allowance "$(jq -cn --arg asset "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" \
  --arg spender "$TOKENRAIL_ESCROW_ADDRESS" '{assetAddress:$asset,spender:$spender,amount:"1500"}')" >/dev/null
payment_release_id="$(id32 payment-release)"
submit_wait investor-b-token /payments payment-fund-release "$(jq -cn --arg id "$payment_release_id" --arg payee "$wallet_a" \
  --arg attestor "$operator" --arg deadline "$(future_iso 300)" \
  '{paymentId:$id,payee:$payee,attestor:$attestor,amount:"1000",deadline:$deadline}')" >/dev/null
submit_wait operator-token "/payments/$payment_release_id/release" payment-release >/dev/null
payment_release="$(wait_projected investor-b-token "/payments/$payment_release_id")"

payment_refund_id="$(id32 payment-refund)"
submit_wait investor-b-token /payments payment-fund-refund "$(jq -cn --arg id "$payment_refund_id" --arg payee "$wallet_a" \
  --arg attestor "$operator" --arg deadline "$(future_iso 6)" \
  '{paymentId:$id,payee:$payee,attestor:$attestor,amount:"500",deadline:$deadline}')" >/dev/null
submit_wait investor-b-token "/payments/$payment_refund_id/refund" payment-early-refund '' REVERTED >/dev/null
sleep 7
submit_wait investor-b-token "/payments/$payment_refund_id/refund" payment-refund >/dev/null
payment_refund="$(wait_projected investor-b-token "/payments/$payment_refund_id")"

# Scenario B: successful DvP followed by an insufficient-balance revert.
submit_wait investor-a-token /allowances dvp-bond-allowance "$(jq -cn --arg asset "$TOKENRAIL_BOND_TOKEN_ADDRESS" \
  --arg spender "$TOKENRAIL_DVP_ADDRESS" '{assetAddress:$asset,spender:$spender,amount:"30"}')" >/dev/null
submit_wait investor-b-token /allowances dvp-cash-allowance "$(jq -cn --arg asset "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" \
  --arg spender "$TOKENRAIL_DVP_ADDRESS" '{assetAddress:$asset,spender:$spender,amount:"30000"}')" >/dev/null
settlement_success_id="$(id32 settlement-success)"
submit_wait investor-a-token /settlements settlement-create "$(jq -cn --arg id "$settlement_success_id" --arg buyer "$wallet_b" \
  --arg expiry "$(future_iso 600)" \
  '{instructionId:$id,buyer:$buyer,bondQuantity:"10",cashAmount:"10000",expiry:$expiry,externalTradeRef:"M7-DVP-SUCCESS"}')" >/dev/null
submit_wait investor-b-token "/settlements/$settlement_success_id/confirm" settlement-confirm >/dev/null
submit_wait operator-token "/settlements/$settlement_success_id/execute" settlement-execute >/dev/null
settlement_success="$(wait_projected investor-a-token "/settlements/$settlement_success_id")"

before_insufficient="$(jq -cn --arg ab "$(balance "$TOKENRAIL_BOND_TOKEN_ADDRESS" "$wallet_a")" \
  --arg bb "$(balance "$TOKENRAIL_BOND_TOKEN_ADDRESS" "$wallet_b")" \
  --arg ac "$(balance "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" "$wallet_a")" \
  --arg bc "$(balance "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" "$wallet_b")" '{aBond:$ab,bBond:$bb,aCash:$ac,bCash:$bc}')"
settlement_insufficient_id="$(id32 settlement-insufficient)"
submit_wait investor-a-token /settlements insufficient-create "$(jq -cn --arg id "$settlement_insufficient_id" --arg buyer "$wallet_b" \
  --arg expiry "$(future_iso 600)" \
  '{instructionId:$id,buyer:$buyer,bondQuantity:"1000",cashAmount:"10000",expiry:$expiry,externalTradeRef:"M7-DVP-INSUFFICIENT"}')" >/dev/null
submit_wait investor-b-token "/settlements/$settlement_insufficient_id/confirm" insufficient-confirm >/dev/null
submit_wait operator-token "/settlements/$settlement_insufficient_id/execute" insufficient-execute '' REVERTED >/dev/null
after_insufficient="$(jq -cn --arg ab "$(balance "$TOKENRAIL_BOND_TOKEN_ADDRESS" "$wallet_a")" \
  --arg bb "$(balance "$TOKENRAIL_BOND_TOKEN_ADDRESS" "$wallet_b")" \
  --arg ac "$(balance "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" "$wallet_a")" \
  --arg bc "$(balance "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" "$wallet_b")" '{aBond:$ab,bBond:$bb,aCash:$ac,bCash:$bc}')"
[[ "$before_insufficient" == "$after_insufficient" ]] || { printf 'Insufficient DvP changed balances\n' >&2; exit 1; }

# Scenario C: freeze blocks DvP and direct transfer; unfreeze allows the READY instruction to retry.
settlement_compliance_id="$(id32 settlement-compliance)"
submit_wait investor-a-token /settlements compliance-create "$(jq -cn --arg id "$settlement_compliance_id" --arg buyer "$wallet_b" \
  --arg expiry "$(future_iso 600)" \
  '{instructionId:$id,buyer:$buyer,bondQuantity:"5",cashAmount:"5000",expiry:$expiry,externalTradeRef:"M7-COMPLIANCE"}')" >/dev/null
submit_wait investor-b-token "/settlements/$settlement_compliance_id/confirm" compliance-confirm >/dev/null
identity_ref="$(cast format-bytes32-string INVESTOR_B)"
identity_body="$(jq -cn --arg ref "$identity_ref" '{participantRef:$ref,validUntil:"2100-01-01T00:00:00Z",active:true,frozen:true,canHoldCash:true,canHoldBond:true}')"
submit_wait operator-token "/identities/$wallet_b/status" compliance-freeze "$identity_body" >/dev/null
submit_wait operator-token "/settlements/$settlement_compliance_id/execute" compliance-frozen-execute '' REVERTED >/dev/null
set +e
direct_receipt="$(cast send --json --legacy --gas-price 0 --gas-limit 200000 --rpc-url "$TOKENRAIL_RPC_URL" \
  --private-key "$(<"$TOKENRAIL_PARTICIPANT_INVESTOR_B_KEY_FILE")" "$TOKENRAIL_DEPOSIT_TOKEN_ADDRESS" \
  'transfer(address,uint256)' "$wallet_a" 1 2>/dev/null)"
direct_code=$?
set -e
direct_status="$(printf '%s\n' "$direct_receipt" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)"
direct_status="${direct_status:-0x0}"
if [[ "$direct_code" -eq 0 && "$(cast to-dec "$direct_status")" != 0 ]]; then
  printf 'Frozen direct transfer unexpectedly succeeded with receipt status %s\n' "$direct_status" >&2
  exit 1
fi
identity_body="$(jq -cn --arg ref "$identity_ref" '{participantRef:$ref,validUntil:"2100-01-01T00:00:00Z",active:true,frozen:false,canHoldCash:true,canHoldBond:true}')"
submit_wait operator-token "/identities/$wallet_b/status" compliance-unfreeze "$identity_body" >/dev/null
submit_wait operator-token "/settlements/$settlement_compliance_id/execute" compliance-retry >/dev/null
settlement_compliance="$(wait_projected investor-a-token "/settlements/$settlement_compliance_id")"

jq -n --slurpfile operations "$operations_file" \
  --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg baseUrl "http://127.0.0.1:$port" \
  --arg paymentReleaseId "$payment_release_id" --arg paymentRefundId "$payment_refund_id" \
  --arg settlementSuccessId "$settlement_success_id" --arg settlementInsufficientId "$settlement_insufficient_id" \
  --arg settlementComplianceId "$settlement_compliance_id" --arg directTransferStatus "$direct_status" \
  --argjson paymentRelease "$payment_release" --argjson paymentRefund "$payment_refund" \
  --argjson settlementSuccess "$settlement_success" --argjson settlementCompliance "$settlement_compliance" \
  --argjson beforeInsufficient "$before_insufficient" --argjson afterInsufficient "$after_insufficient" \
  '{generatedAt:$generatedAt,baseUrl:$baseUrl,scenarioA:{releaseId:$paymentReleaseId,refundId:$paymentRefundId,release:$paymentRelease,refund:$paymentRefund},scenarioB:{successId:$settlementSuccessId,insufficientId:$settlementInsufficientId,success:$settlementSuccess,beforeInsufficient:$beforeInsufficient,afterInsufficient:$afterInsufficient},scenarioC:{settlementId:$settlementComplianceId,directTransferStatus:$directTransferStatus,settlement:$settlementCompliance},operations:$operations}' \
  > "$output_dir/m7-scenario-results.json"

printf 'M7 scenarios passed. Evidence: %s\n' "$output_dir/m7-scenario-results.json"
if [[ "$keep_running" == true ]]; then
  printf '%s\n' "$server_pid" > "$output_dir/server.pid"
  server_pid=""
  printf 'Demo server remains available at http://127.0.0.1:%s/\n' "$port"
fi
