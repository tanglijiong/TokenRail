#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pwcli="${PWCLI:-$HOME/.codex/skills/playwright/scripts/playwright_cli.sh}"
port="${TOKENRAIL_DEMO_PORT:-18081}"
work_dir="$repo_root/output/playwright/m7"
demo_dir="$repo_root/output/demo"
evidence_dir="$repo_root/docs/evidence/m7-release-20260914"
session="tokenrail-m7"

for command_name in npx jq ffmpeg ffprobe; do
  command -v "$command_name" >/dev/null 2>&1 || { printf '%s is required.\n' "$command_name" >&2; exit 1; }
done
[[ -x "$pwcli" ]] || { printf 'Playwright CLI wrapper not found: %s\n' "$pwcli" >&2; exit 1; }

if [[ -f "$demo_dir/server.pid" ]]; then
  old_pid="$(cat "$demo_dir/server.pid")"
  kill "$old_pid" 2>/dev/null || true
  wait "$old_pid" 2>/dev/null || true
fi
rm -rf "$work_dir"
mkdir -p "$work_dir" "$evidence_dir"
TOKENRAIL_DEMO_PORT="$port" "$repo_root/scripts/demo-m7-scenarios.sh" --keep-running

report="$demo_dir/m7-scenario-results.json"
release_id="$(jq -r '.scenarioA.releaseId' "$report")"
refund_id="$(jq -r '.scenarioA.refundId' "$report")"
success_id="$(jq -r '.scenarioB.successId' "$report")"
compliance_id="$(jq -r '.scenarioC.settlementId' "$report")"

cd "$work_dir"
"$pwcli" --session "$session" open "http://127.0.0.1:$port/?actor=operator" --headed
"$pwcli" --session "$session" resize 1280 720

cat > capture.js <<EOF
async (page) => {
  const frames = [
    ['http://127.0.0.1:$port/?actor=operator', '01-overview.png', null],
    ['http://127.0.0.1:$port/payments.html?actor=investorB&id=$release_id', '02-payment-release.png', '#payment-result'],
    ['http://127.0.0.1:$port/payments.html?actor=investorB&id=$refund_id', '03-payment-refund.png', '#payment-result'],
    ['http://127.0.0.1:$port/settlement.html?actor=investorA&id=$success_id', '04-dvp-success.png', '#settlement-result'],
    ['http://127.0.0.1:$port/operations.html?actor=operator', '05-rejected-operations.png', null],
    ['http://127.0.0.1:$port/settlement.html?actor=investorA&id=$compliance_id', '06-compliance-retry.png', '#settlement-result']
  ];
  for (const [url, filename, target] of frames) {
    await page.goto(url);
    await page.waitForTimeout(900);
    if (target) await page.locator(target).scrollIntoViewIfNeeded();
    await page.screenshot({ path: filename, type: 'png' });
  }
}
EOF
"$pwcli" --session "$session" run-code --filename capture.js
"$pwcli" --session "$session" snapshot >/dev/null
"$pwcli" --session "$session" console error > console.txt
"$pwcli" --session "$session" close

if grep -q '\[ERROR\]' console.txt; then
  printf 'Browser console contains errors; see %s/console.txt\n' "$work_dir" >&2
  exit 1
fi

cat > frames.txt <<EOF
file '$work_dir/01-overview.png'
duration 3
file '$work_dir/02-payment-release.png'
duration 4
file '$work_dir/03-payment-refund.png'
duration 4
file '$work_dir/04-dvp-success.png'
duration 4
file '$work_dir/05-rejected-operations.png'
duration 4
file '$work_dir/06-compliance-retry.png'
duration 5
file '$work_dir/06-compliance-retry.png'
EOF

video="$evidence_dir/tokenrail-v0.1-demo.mp4"
ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i frames.txt \
  -vf "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=0xf4f6fb,format=yuv420p" \
  -r 30 -c:v libx264 -preset medium -crf 22 -movflags +faststart "$video"

ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height \
  -show_entries format=duration,size -of json "$video" > "$evidence_dir/video-metadata.json"
cp "$report" "$evidence_dir/scenario-results.json"
printf 'M7 demo recording created: %s\n' "$video"
