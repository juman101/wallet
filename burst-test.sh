#!/usr/bin/env bash
#
# One-command burst script reproducing the evaluator's live concurrency gates against a
# running instance of the wallet service.
#
# Usage:
#   ./burst-test.sh                                   # against http://localhost:8080
#   BASE_URL=https://your-deployed-url ./burst-test.sh # against a deployed instance
#
# Requires: bash, curl. No jq dependency - responses are simple flat JSON, parsed with grep/sed.

set -u
BASE_URL="${BASE_URL:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0

pass() { echo "[PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "[FAIL] $1"; FAIL=$((FAIL + 1)); }

json_field() {
  # json_field '<json>' field_name -> value (string or number, unquoted)
  local json="$1" field="$2"
  echo "$json" | grep -o "\"$field\":\"[^\"]*\"" | head -1 | sed -E "s/\"$field\":\"([^\"]*)\"/\1/" \
    || echo "$json" | grep -o "\"$field\":[0-9-]*" | head -1 | sed -E "s/\"$field\":([0-9-]*)/\1/"
}

new_user() { echo "burst-$(date +%s%N)-$RANDOM"; }

create_wallet() {
  local user="$1"
  curl -s -X POST "$BASE_URL/wallets" -H "Authorization: Bearer $user"
}

deposit() {
  local wallet_id="$1" amount="$2"
  curl -s -X POST "$BASE_URL/wallets/$wallet_id/deposit" \
    -H "Authorization: Bearer any" -H "Content-Type: application/json" \
    -d "{\"amount_paise\":$amount}"
}

get_wallet() {
  curl -s "$BASE_URL/wallets/$1" -H "Authorization: Bearer any"
}

balance_of() {
  json_field "$(get_wallet "$1")" balance_paise
}

post_transfer() {
  local from="$1" to="$2" amount="$3" key="$4"
  curl -s -o "$TMP_DIR/body_$key" -w "%{http_code}" -X POST "$BASE_URL/transfers" \
    -H "Authorization: Bearer any" -H "Content-Type: application/json" \
    -d "{\"from\":\"$from\",\"to\":\"$to\",\"amount_paise\":$amount,\"idempotency_key\":\"$key\"}"
}
export -f post_transfer json_field
export BASE_URL TMP_DIR

echo "== Wallet & P2P Transfer burst test against $BASE_URL =="
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 1: concurrent get-or-create (50x, same brand-new user) ---"
USER1="$(new_user)"
for i in $(seq 1 50); do
  create_wallet "$USER1" > "$TMP_DIR/wallet_$i.json" &
done
wait

sort -u -o "$TMP_DIR/wallet_ids.txt" <(for i in $(seq 1 50); do json_field "$(cat "$TMP_DIR/wallet_$i.json")" wallet_id; done)
DISTINCT_WALLETS=$(wc -l < "$TMP_DIR/wallet_ids.txt" | tr -d ' ')
if [ "$DISTINCT_WALLETS" = "1" ]; then
  pass "concurrent get-or-create -> exactly 1 wallet"
else
  fail "concurrent get-or-create -> got $DISTINCT_WALLETS distinct wallets (expected 1)"
fi
WALLET1="$(cat "$TMP_DIR/wallet_ids.txt")"
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 2: idempotency storm (30x same key, same body) ---"
USER_A="$(new_user)"; USER_B="$(new_user)"
WALLET_A="$(json_field "$(create_wallet "$USER_A")" wallet_id)"
WALLET_B="$(json_field "$(create_wallet "$USER_B")" wallet_id)"
deposit "$WALLET_A" 100000 > /dev/null
KEY="storm-$(date +%s%N)"
for i in $(seq 1 30); do
  post_transfer "$WALLET_A" "$WALLET_B" 1000 "$KEY" > "$TMP_DIR/storm_code_$i" &
done
wait

DISTINCT_BODIES=$(sort -u "$TMP_DIR"/body_"$KEY" 2>/dev/null | wc -l | tr -d ' ')
BAL_A=$(balance_of "$WALLET_A")
BAL_B=$(balance_of "$WALLET_B")
if [ "$BAL_A" = "99000" ] && [ "$BAL_B" = "1000" ]; then
  pass "idempotency storm -> exactly one debit/credit applied (A=$BAL_A, B=$BAL_B)"
else
  fail "idempotency storm -> A=$BAL_A B=$BAL_B (expected A=99000 B=1000)"
fi
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 3: same idempotency key, different body -> 409 ---"
KEY2="conflict-$(date +%s%N)"
post_transfer "$WALLET_A" "$WALLET_B" 500 "$KEY2" > "$TMP_DIR/conflict1_code"
CODE1=$(cat "$TMP_DIR/conflict1_code")
post_transfer "$WALLET_A" "$WALLET_B" 999 "$KEY2" > "$TMP_DIR/conflict2_code"
CODE2=$(cat "$TMP_DIR/conflict2_code")
BAL_A_AFTER=$(balance_of "$WALLET_A")
if [ "$CODE1" = "201" ] && [ "$CODE2" = "409" ] && [ "$BAL_A_AFTER" = "98500" ]; then
  pass "same-key different-body -> 409, balance moved only once (A=$BAL_A_AFTER)"
else
  fail "same-key different-body -> codes $CODE1/$CODE2, A=$BAL_A_AFTER (expected 201/409, A=98500)"
fi
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 4: no overdraft ---"
USER_C="$(new_user)"
WALLET_C="$(json_field "$(create_wallet "$USER_C")" wallet_id)"
deposit "$WALLET_C" 100 > /dev/null
post_transfer "$WALLET_C" "$WALLET_B" 999999 "overdraft-$(date +%s%N)" > "$TMP_DIR/overdraft_code"
OVERDRAFT_CODE=$(cat "$TMP_DIR/overdraft_code")
OVERDRAFT_BALANCE=$(balance_of "$WALLET_C")
if [ "$OVERDRAFT_CODE" = "201" ] && [ "$OVERDRAFT_BALANCE" = "100" ]; then
  pass "overdraft -> cleanly declined, source balance untouched ($OVERDRAFT_BALANCE)"
else
  fail "overdraft -> code=$OVERDRAFT_CODE balance=$OVERDRAFT_BALANCE (expected 201, balance=100 unchanged)"
fi
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 5: conservation under contention (A<->B, 80 concurrent transfers) ---"
BEFORE_TOTAL=$(( $(balance_of "$WALLET_A") + $(balance_of "$WALLET_B") ))
for i in $(seq 1 40); do
  post_transfer "$WALLET_A" "$WALLET_B" 100 "cross-ab-$i-$(date +%s%N)" > /dev/null &
  post_transfer "$WALLET_B" "$WALLET_A" 100 "cross-ba-$i-$(date +%s%N)" > /dev/null &
done
wait
AFTER_TOTAL=$(( $(balance_of "$WALLET_A") + $(balance_of "$WALLET_B") ))
NEG_A=$(balance_of "$WALLET_A"); NEG_B=$(balance_of "$WALLET_B")
if [ "$BEFORE_TOTAL" = "$AFTER_TOTAL" ] && [ "$NEG_A" -ge 0 ] && [ "$NEG_B" -ge 0 ]; then
  pass "conservation under contention -> total unchanged ($BEFORE_TOTAL == $AFTER_TOTAL), no negative balance"
else
  fail "conservation under contention -> before=$BEFORE_TOTAL after=$AFTER_TOTAL A=$NEG_A B=$NEG_B"
fi
echo

# --------------------------------------------------------------------------------------------
echo "--- Test 6: A->B / B->A contention produces no deadlock 5xx ---"
FIVE_XX=0
for i in $(seq 1 40); do
  post_transfer "$WALLET_A" "$WALLET_B" 10 "dl-ab-$i-$(date +%s%N)" > "$TMP_DIR/dl_ab_$i" &
  post_transfer "$WALLET_B" "$WALLET_A" 10 "dl-ba-$i-$(date +%s%N)" > "$TMP_DIR/dl_ba_$i" &
done
wait
for i in $(seq 1 40); do
  c1=$(cat "$TMP_DIR/dl_ab_$i" 2>/dev/null || echo "000")
  c2=$(cat "$TMP_DIR/dl_ba_$i" 2>/dev/null || echo "000")
  [[ "$c1" == 5* ]] && FIVE_XX=$((FIVE_XX + 1))
  [[ "$c2" == 5* ]] && FIVE_XX=$((FIVE_XX + 1))
done
if [ "$FIVE_XX" = "0" ]; then
  pass "A->B / B->A contention -> no 5xx (deadlock-free)"
else
  fail "A->B / B->A contention -> $FIVE_XX requests returned 5xx"
fi
echo

echo "== Summary: $PASS passed, $FAIL failed =="
[ "$FAIL" = "0" ]
