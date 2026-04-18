#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# test-rate-limit.sh  —  Manual smoke-test for the Token Bucket rate limiter
#
# Usage:
#   chmod +x test-rate-limit.sh
#   ./test-rate-limit.sh [BASE_URL]          # default: http://localhost:8080
#
# What it does:
#   1. Fires 15 rapid requests at /api/test
#      → First 10 should return HTTP 200 (capacity default)
#      → Remaining should return HTTP 429
#   2. Waits 3 seconds (refill) and fires 5 more
#      → Some (up to refillRate × 3s = 15 tokens max) should return 200 again
#   3. Tests per-IP isolation by spoofing X-Forwarded-For
# ─────────────────────────────────────────────────────────────────────────────

BASE_URL="${1:-http://localhost:8080}"
ENDPOINT="$BASE_URL/api/test"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[200 OK]${NC} $1"; }
fail() { echo -e "${RED}[429 LIMIT]${NC} $1"; }
info() { echo -e "${YELLOW}>>>  $1${NC}"; }

# ── Phase 1: Rapid burst ─────────────────────────────────────────────────────
info "Phase 1: Sending 15 rapid requests from IP 10.1.1.1 (capacity=10)"
for i in $(seq 1 15); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-Forwarded-For: 10.1.1.1" "$ENDPOINT")
  if [ "$STATUS" = "200" ]; then
    pass "Request #$i → $STATUS"
  else
    fail "Request #$i → $STATUS"
  fi
done

echo ""
# ── Phase 2: Refill window ───────────────────────────────────────────────────
info "Phase 2: Waiting 3 seconds for token refill..."
sleep 3

info "Sending 6 more requests after refill (expect some 200s)"
for i in $(seq 1 6); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-Forwarded-For: 10.1.1.1" "$ENDPOINT")
  if [ "$STATUS" = "200" ]; then
    pass "Post-refill request #$i → $STATUS"
  else
    fail "Post-refill request #$i → $STATUS"
  fi
done

echo ""
# ── Phase 3: Per-IP isolation ────────────────────────────────────────────────
info "Phase 3: Testing per-IP isolation"
info "  10.1.1.1 bucket is drained. 10.2.2.2 should still have full capacity."
for i in $(seq 1 3); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "X-Forwarded-For: 10.2.2.2" "$ENDPOINT")
  if [ "$STATUS" = "200" ]; then
    pass "10.2.2.2 request #$i → $STATUS (independent bucket)"
  else
    fail "10.2.2.2 request #$i → $STATUS (UNEXPECTED — should be 200)"
  fi
done

echo ""
info "Done. Adjust application.yml (capacity, refill-rate) and re-run."
