#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test_idempotency.sh
# Manual end-to-end test script for the Crescent Pharmacy Idempotency Service
#
# Prerequisites: running service on localhost:8080 (docker-compose up or ./mvnw spring-boot:run)
# Usage:         bash scripts/test_idempotency.sh
# ─────────────────────────────────────────────────────────────────────────────

BASE_URL="http://localhost:8080/api/v1"
MERCHANT_ID="PHARMACY_001"
PASS=0; FAIL=0

color_green() { echo -e "\033[32m$*\033[0m"; }
color_red()   { echo -e "\033[31m$*\033[0m"; }
color_blue()  { echo -e "\033[34m$*\033[0m"; }

pass() { color_green "  ✓ PASS: $1"; ((PASS++)); }
fail() { color_red   "  ✗ FAIL: $1"; ((FAIL++)); }

# ─────────────────────────────────────────────────────────────────────────────
color_blue "
╔═══════════════════════════════════════════════════════════════╗
║  Crescent Pharmacy – Payment Idempotency Service Test Suite   ║
╚═══════════════════════════════════════════════════════════════╝
"

# ─── Test 1: New Payment ──────────────────────────────────────────────────────
color_blue "Test 1: New payment request"
IDEM_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/payments" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $IDEM_KEY" \
  -H "X-Merchant-Id: $MERCHANT_ID" \
  -d '{
    "customerId": "CUST_0001",
    "amount": 45.00,
    "currency": "EGP",
    "paymentMethod": { "type": "CARD", "cardLastFour": "4242", "cardBrand": "VISA" },
    "description": "Prescription pickup – Test 1"
  }')

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -1)

echo "  HTTP: $HTTP_CODE"
echo "  Body: $BODY"

if [[ "$HTTP_CODE" == "201" ]]; then
  pass "First payment returns 201 Created"
else
  fail "Expected 201, got $HTTP_CODE"
fi

IS_DUP=$(echo "$BODY" | grep -o '"duplicate":false')
if [[ -n "$IS_DUP" ]]; then
  pass "duplicate=false for new payment"
else
  fail "Expected duplicate=false"
fi

# ─── Test 2: Duplicate of Test 1 (same idempotency key) ──────────────────────
color_blue ""
color_blue "Test 2: Duplicate request (same key as Test 1)"

RESPONSE2=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/payments" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $IDEM_KEY" \
  -H "X-Merchant-Id: $MERCHANT_ID" \
  -d '{
    "customerId": "CUST_0001",
    "amount": 45.00,
    "currency": "EGP",
    "paymentMethod": { "type": "CARD", "cardLastFour": "4242", "cardBrand": "VISA" },
    "description": "Prescription pickup – Test 1 (retry)"
  }')

HTTP_CODE2=$(echo "$RESPONSE2" | tail -1)
BODY2=$(echo "$RESPONSE2" | head -1)

echo "  HTTP: $HTTP_CODE2"
echo "  Body: $BODY2"

if [[ "$HTTP_CODE2" == "200" ]]; then
  pass "Duplicate returns 200 OK"
else
  fail "Expected 200, got $HTTP_CODE2"
fi

IS_DUP2=$(echo "$BODY2" | grep -o '"duplicate":true')
if [[ -n "$IS_DUP2" ]]; then
  pass "duplicate=true for repeated request"
else
  fail "Expected duplicate=true"
fi

# Verify both responses have the SAME paymentId
PAY_ID1=$(echo "$BODY"  | grep -o '"paymentId":"[^"]*"' | head -1)
PAY_ID2=$(echo "$BODY2" | grep -o '"paymentId":"[^"]*"' | head -1)
if [[ "$PAY_ID1" == "$PAY_ID2" && -n "$PAY_ID1" ]]; then
  pass "Both responses share the same paymentId ($PAY_ID1)"
else
  fail "paymentId mismatch: [$PAY_ID1] vs [$PAY_ID2]"
fi

# ─── Test 3: Third duplicate (5 total requests for same key) ─────────────────
color_blue ""
color_blue "Test 3: Sending 3 more duplicates (5 total for same key)"

for i in 3 4 5; do
  R=$(curl -s -X POST "$BASE_URL/payments" \
    -H "Content-Type: application/json" \
    -H "X-Idempotency-Key: $IDEM_KEY" \
    -H "X-Merchant-Id: $MERCHANT_ID" \
    -d '{"customerId":"CUST_0001","amount":45.00,"currency":"EGP","paymentMethod":{"type":"CARD","cardLastFour":"4242","cardBrand":"VISA"}}')
  IS_D=$(echo "$R" | grep -o '"duplicate":true')
  if [[ -n "$IS_D" ]]; then
    pass "Request $i: duplicate=true"
  else
    fail "Request $i: expected duplicate=true"
  fi
done

# ─── Test 4: Different key → new payment ─────────────────────────────────────
color_blue ""
color_blue "Test 4: New idempotency key → new payment (not a duplicate)"
NEW_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

R4=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/payments" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $NEW_KEY" \
  -H "X-Merchant-Id: $MERCHANT_ID" \
  -d '{"customerId":"CUST_0002","amount":120.50,"currency":"MAD","paymentMethod":{"type":"MOBILE_WALLET","walletType":"FAWRY","walletPhoneNumber":"01012345678"}}')

HTTP_R4=$(echo "$R4" | tail -1)
BODY_R4=$(echo "$R4" | head -1)

if [[ "$HTTP_R4" == "201" ]]; then
  pass "New key returns 201"
else
  fail "Expected 201, got $HTTP_R4"
fi

# ─── Test 5: Get payment status by key ───────────────────────────────────────
color_blue ""
color_blue "Test 5: GET payment status by idempotency key"

STATUS_R=$(curl -s -w "\n%{http_code}" "$BASE_URL/payments/$IDEM_KEY" \
  -H "X-Merchant-Id: $MERCHANT_ID")

HTTP_S=$(echo "$STATUS_R" | tail -1)
if [[ "$HTTP_S" == "200" ]]; then
  pass "GET /payments/{key} returns 200"
else
  fail "Expected 200, got $HTTP_S"
fi

# ─── Test 6: Missing idempotency key header ───────────────────────────────────
color_blue ""
color_blue "Test 6: Missing X-Idempotency-Key header → 400 Bad Request"

R6=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/payments" \
  -H "Content-Type: application/json" \
  -H "X-Merchant-Id: $MERCHANT_ID" \
  -d '{"customerId":"CUST_0003","amount":10.00,"currency":"TND","paymentMethod":{"type":"CARD","cardLastFour":"1111","cardBrand":"VISA"}}')

HTTP_R6=$(echo "$R6" | tail -1)
if [[ "$HTTP_R6" == "400" ]]; then
  pass "Missing header returns 400"
else
  fail "Expected 400, got $HTTP_R6"
fi

# ─── Test 7: Audit trail for a key ───────────────────────────────────────────
color_blue ""
color_blue "Test 7: GET audit trail for a key"

AUDIT_R=$(curl -s -w "\n%{http_code}" "$BASE_URL/payments/$IDEM_KEY/audit" \
  -H "X-Merchant-Id: $MERCHANT_ID")

HTTP_A=$(echo "$AUDIT_R" | tail -1)
if [[ "$HTTP_A" == "200" ]]; then
  pass "GET /payments/{key}/audit returns 200"
else
  fail "Expected 200, got $HTTP_A"
fi

EVENTS=$(echo "$AUDIT_R" | head -1 | grep -o '"eventType"' | wc -l | tr -d ' ')
color_blue "  Audit events recorded for this key: $EVENTS"

# ─── Test 8: Statistics endpoint ─────────────────────────────────────────────
color_blue ""
color_blue "Test 8: GET /api/v1/stats"

STATS_R=$(curl -s -w "\n%{http_code}" "$BASE_URL/stats")
HTTP_ST=$(echo "$STATS_R" | tail -1)
BODY_ST=$(echo "$STATS_R" | head -1)

if [[ "$HTTP_ST" == "200" ]]; then
  pass "GET /stats returns 200"
  DUP_COUNT=$(echo "$BODY_ST" | grep -o '"totalDuplicatesBlocked":[0-9]*' | grep -o '[0-9]*$')
  color_blue "  totalDuplicatesBlocked = $DUP_COUNT"
else
  fail "Expected 200, got $HTTP_ST"
fi

# ─── Test 9: Generate 100 test payments ──────────────────────────────────────
color_blue ""
color_blue "Test 9: Generate 100 mixed payments via /api/v1/test/generate"

GEN_R=$(curl -s -w "\n%{http_code}" -X POST \
  "$BASE_URL/test/generate?count=100&merchantId=PHARMACY_001")

HTTP_G=$(echo "$GEN_R" | tail -1)
BODY_G=$(echo "$GEN_R" | head -1)

if [[ "$HTTP_G" == "200" ]]; then
  pass "Test data generation returned 200"
  echo "  $BODY_G"
else
  fail "Expected 200, got $HTTP_G"
fi

# ─── Test 10: Concurrent test ─────────────────────────────────────────────────
color_blue ""
color_blue "Test 10: Concurrent duplicate test (10 threads)"

CONC_R=$(curl -s -w "\n%{http_code}" -X POST \
  "$BASE_URL/test/concurrent?concurrency=10&merchantId=PHARMACY_001")

HTTP_C=$(echo "$CONC_R" | tail -1)
BODY_C=$(echo "$CONC_R" | head -1)

if [[ "$HTTP_C" == "200" ]]; then
  pass "Concurrent test endpoint returned 200"
  echo "  $BODY_C"
  IDEM_OK=$(echo "$BODY_C" | grep -o '"idempotencySuccessful":true')
  if [[ -n "$IDEM_OK" ]]; then
    pass "Idempotency maintained under concurrent load"
  else
    fail "idempotencySuccessful was not true"
  fi
else
  fail "Expected 200, got $HTTP_C"
fi

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
color_blue "════════════════════════════════════════"
color_green "  PASSED: $PASS"
if [[ $FAIL -gt 0 ]]; then
  color_red "  FAILED: $FAIL"
else
  color_green "  FAILED: $FAIL"
fi
color_blue "════════════════════════════════════════"

[[ $FAIL -eq 0 ]] && exit 0 || exit 1
