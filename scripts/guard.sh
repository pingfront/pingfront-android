#!/usr/bin/env bash
# Source guards for the Android SDK (the same rules as the iOS SDK's guard). Runs in `./gradlew check`.
# Each rule greps the SDK's main sources (not tests, fixtures or the demo) and fails with a reason.
set -euo pipefail
scripts="$(cd "$(dirname "$0")" && pwd)"
# The SDK root (default: this script's parent); scripts/guard-test.sh points it at fixture trees.
cd "${1:-$(dirname "$0")/..}"

main_sources() { find pingfront*/src/main -name '*.kt'; }
fail=0
check() { # name, pattern, [path filter to exclude]
  local name="$1" pattern="$2" exclude="${3:-}"
  local hits
  hits=$(main_sources | { if [ -n "$exclude" ]; then grep -v -E "$exclude"; else cat; fi; } | xargs grep -n -E "$pattern" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "guard: $name"
    echo "$hits" | sed 's/^/  /'
    fail=1
  fi
}

# Requests go through the typed endpoint layer only.
check "stringly-typed request methods outside internal/net" '"(GET|POST|PATCH|PUT|DELETE)"' '/internal/net/'
check "raw API paths outside internal/net" '"/?(thread|sessions|me|uploads|reports|moments|attachments)/[^"]*"' '/internal/net/'
# Log privacy: never server messages, URLs, tokens or emails.
check "logging a server message, URL, token or email" 'Log\.[a-z]+\(.*\$\{?[A-Za-z_.]*(e\.message|error\.message|[uU]rl|[tT]oken|[eE]mail|[sS]ecret)'
check "android.util.Log outside the SDK logger" 'android\.util\.Log\.(v|d|i|w|e|println)\(' '/internal/runtime/Log\.kt'
# Secrets live in the Keystore-sealed DataStore only.
check "SharedPreferences for SDK data" 'getSharedPreferences|PreferenceManager'
check "EncryptedSharedPreferences (deprecated)" 'EncryptedSharedPreferences|MasterKey'
# Private chat images are never cached on disk.
check "disk caching of chat images" '(cacheDir|filesDir|externalCacheDir).*(image|photo|avatar)|DiskLruCache' 'PhotoViewer\.kt'
# Never acknowledge or consume the host app's purchases. The one sanctioned path: Billing.kt acknowledges the purchases
# its own purchase sheet started (Play refunds unacknowledged purchases after three days), in one place.
SANCTIONED="pingfront-billing/src/main/kotlin/dev/pingfront/billing/Billing.kt"
check "consuming purchases" 'consumeAsync|consumePurchase'
check "acknowledging purchases outside the SDK's own purchase sheet ($SANCTIONED)" 'acknowledgePurchase' "$SANCTIONED"
if [ -f "$SANCTIONED" ] && [ "$(grep -c 'acknowledgePurchase' "$SANCTIONED")" -gt 1 ]; then
  echo "guard: acknowledgePurchase more than once in $SANCTIONED (keep the single sanctioned call in PlayBilling.acknowledge)"
  fail=1
fi
# No global scope or blocking in library code.
check "GlobalScope or runBlocking" 'GlobalScope|runBlocking'
# Dispatchers and clocks are injected (SdkDispatchers, TimeSource), so tests run on virtual time (review AN-20).
check "a hard-coded dispatcher outside SdkDispatchers.Production" 'Dispatchers\.(IO|Default|Main|Unconfined)' '/internal/runtime/Time\.kt'
check "the platform clock outside TimeSource" 'SystemClock(\.|::)|System\.currentTimeMillis' '/internal/runtime/Time\.kt'

# REFERENCE.md is generated from the API dumps; a stale one fails (fixture trees in guard-test.sh have none).
if [ -f REFERENCE.md ] && [ -f pingfront-core/api/pingfront-core.api ]; then
  if ! python3 "$scripts/generate-reference.py" --check; then
    echo "guard: REFERENCE.md does not match the API dumps"
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "guard: FAILED"
  exit 1
fi
echo "guard: OK"
