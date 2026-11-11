#!/usr/bin/env bash
# Tests for scripts/guard.sh's purchase rules: the SDK acknowledges only through the one sanctioned call in
# pingfront-billing's Billing.kt, and never consumes. Runs before guard.sh in `./gradlew guard`.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

billing="pingfront-billing/src/main/kotlin/dev/pingfront/billing/Billing.kt"
other="pingfront-core/src/main/kotlin/dev/pingfront/Other.kt"

# case name, expected (pass|fail), then path=content pairs
run() {
  local name="$1" want="$2"
  shift 2
  local root="$tmp/$name"
  mkdir -p "$root"
  for pair in "$@"; do
    local path="${pair%%=*}" body="${pair#*=}"
    mkdir -p "$root/$(dirname "$path")"
    printf '%s\n' "$body" >"$root/$path"
  done
  local got=pass
  bash "$here/guard.sh" "$root" >/dev/null 2>&1 || got=fail
  if [ "$got" != "$want" ]; then
    echo "guard-test: $name: guard said $got, want $want"
    exit 1
  fi
}

run sanctioned-acknowledge pass "$billing=client.acknowledgePurchase(params) { }"
run acknowledge-elsewhere fail "$billing=fun x() = 1" "$other=client.acknowledgePurchase(params) { }"
run acknowledge-twice fail "$billing=client.acknowledgePurchase(a) { }
client.acknowledgePurchase(b) { }"
run consume-in-billing fail "$billing=client.consumeAsync(params) { _, _ -> }"
run consume-elsewhere fail "$other=client.consumePurchase(params)"
run dispatcher-in-a-module fail "$other=var io = Dispatchers.IO"
run dispatchers-in-time-kt pass "pingfront-core/src/main/kotlin/dev/pingfront/internal/runtime/Time.kt=val p = Dispatchers.Default"
run platform-clock fail "$other=val clock = android.os.SystemClock::elapsedRealtime"
echo "guard-test: OK"
