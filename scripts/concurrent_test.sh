#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# concurrent_test.sh
# Demonstrates race-condition safety by hammering the service with N parallel
# requests sharing the same idempotency key.
#
# Prerequisites: running service on localhost:8080
# Usage:         bash scripts/concurrent_test.sh [concurrency] [merchant]
# Example:       bash scripts/concurrent_test.sh 20 PHARMACY_005
# ─────────────────────────────────────────────────────────────────────────────

BASE_URL="http://localhost:8080/api/v1"
CONCURRENCY="${1:-10}"
MERCHANT_ID="${2:-PHARMACY_001}"
SHARED_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
TMP_DIR=$(mktemp -d)

echo "════════════════════════════════════════════════════════════"
echo "  Concurrent Idempotency Test"
echo "  Shared Key  : $SHARED_KEY"
echo "  Merchant    : $MERCHANT_ID"
echo "  Threads     : $CONCURRENCY"
echo "════════════════════════════════════════════════════════════"

# Fire all requests in parallel
for i in $(seq 1 "$CONCURRENCY"); do
  (
    RESP=$(curl -s -X POST "$BASE_URL/payments" \
      -H "Content-Type: application/json" \
      -H "X-Idempotency-Key: $SHARED_KEY" \
      -H "X-Merchant-Id: $MERCHANT_ID" \
      -d "{
        \"customerId\":\"CUST_CONCURRENT\",
        \"amount\":75.00,
        \"currency\":\"EGP\",
        \"paymentMethod\":{\"type\":\"CARD\",\"cardLastFour\":\"4242\",\"cardBrand\":\"VISA\"},
        \"description\":\"Concurrent test – thread $i\"
      }")
    echo "$RESP" > "$TMP_DIR/response_$i.json"
  ) &
done

wait  # Wait for all background jobs

echo ""
echo "── Results ──────────────────────────────────────────────────"
NEW_COUNT=0
DUP_COUNT=0
declare -A PAY_IDS

for i in $(seq 1 "$CONCURRENCY"); do
  RESP=$(cat "$TMP_DIR/response_$i.json" 2>/dev/null)
  PAY_ID=$(echo "$RESP"   | grep -o '"paymentId":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"')
  IS_DUP=$(echo "$RESP"   | grep -o '"duplicate":true')
  STATUS=$(echo "$RESP"   | grep -o '"status":"[^"]*"'   | grep -o '"[^"]*"$' | tr -d '"')

  if [[ -n "$IS_DUP" ]]; then
    echo "  Thread $i: DUPLICATE   status=$STATUS  paymentId=$PAY_ID"
    ((DUP_COUNT++))
  else
    echo "  Thread $i: NEW CHARGE  status=$STATUS  paymentId=$PAY_ID"
    ((NEW_COUNT++))
  fi

  [[ -n "$PAY_ID" ]] && PAY_IDS["$PAY_ID"]=1
done

UNIQUE_IDS=${#PAY_IDS[@]}

echo ""
echo "── Summary ──────────────────────────────────────────────────"
echo "  New charges created : $NEW_COUNT"
echo "  Duplicates blocked  : $DUP_COUNT"
echo "  Unique payment IDs  : $UNIQUE_IDS"
echo ""

if [[ $NEW_COUNT -le 1 && $UNIQUE_IDS -le 1 ]]; then
  echo -e "\033[32m  ✓ PASS – Idempotency maintained: only 1 charge created despite $CONCURRENCY concurrent requests\033[0m"
  RESULT=0
else
  echo -e "\033[31m  ✗ FAIL – $NEW_COUNT charges created / $UNIQUE_IDS unique IDs (expected 1 each)\033[0m"
  RESULT=1
fi

# Clean up
rm -rf "$TMP_DIR"
exit $RESULT
