# Adversarial review

Ten failure scenarios (what happens / why it's safe / which DB guarantee proves it), then the
debrief Q&A, both pointing at actual code and tests rather than assertions.

## 1. Two concurrent wallet creations

**What happens:** Both `POST /wallets` calls race to `INSERT ... ON CONFLICT (user_id) DO
NOTHING`. One inserts and commits; the other's insert blocks on the uncommitted duplicate key,
then resolves to "no-op" once the winner commits, and both requests then `SELECT` the same row.

**Why safe:** `UNIQUE(user_id)` is a hard database constraint, not an application check. There is
no gap between "check" and "insert" for two requests to both fall through.

**Proof:** `wallets.uq_wallets_user_id` (`V1__init.sql`), `WalletRepository.insertIfAbsent`,
`WalletConcurrencyTest.fiftyConcurrentGetOrCreateForSameNewUserYieldExactlyOneWallet` (50-way).

## 2. Two concurrent transfers, same idempotency key

**What happens:** Same pattern as #1 but on `transfers.idempotency_key`. One request's `INSERT
... ON CONFLICT DO NOTHING` wins and proceeds to debit/credit inside its transaction; every other
concurrent request with that key blocks at the `INSERT`, then reads back the (by now committed)
final row and returns it verbatim.

**Why safe:** The unique constraint plus the fact that Postgres blocks the losing insert on the
uncommitted duplicate — not a race, a documented `ON CONFLICT` guarantee.

**Proof:** `transfers.uq_transfers_idempotency_key`, `TransferService.createTransfer` (the
`won`/`!won` branch), `TransferIdempotencyTest.thirtyConcurrentRetriesWithSameKeyAndBodyProduceExactlyOneDebitAndCredit`.

## 3. Same idempotency key, different request body

**What happens:** The loser's `request_hash` (computed from its own from/to/amount) is compared
against the winner's stored `request_hash`. Mismatch → `IdempotencyConflictException` → `409`.
No money moves for the second request.

**Why safe:** The hash is computed and compared inside the same transaction as the read of the
existing row, so there's no window for a third request to interleave and change the answer.

**Proof:** `RequestHashUtil.sha256`, `TransferService.createTransfer` (hash comparison),
`TransferIdempotencyTest.sameKeyWithDifferentBodyReturns409AndDoesNotDoubleApply`.

## 4. A→B and B→A simultaneously

**What happens:** Both transactions sort the wallet pair and lock ascending-id-first. Whichever
transaction gets there first acquires lock(low), forcing the other to wait for lock(low) before
it can even attempt lock(high). No transaction ever holds one lock while waiting on a lock the
other already holds in the opposite order — the cycle that causes deadlock can't form.

**Why safe:** Deterministic lock order is a standard, provably deadlock-free discipline for
this exact two-resource contention pattern.

**Proof:** `TransferService.createTransfer` (`firstLockId`/`secondLockId` sorted by `UUID`
comparison), `TransferConservationTest.concurrentOppositeDirectionTransfersDoNotDeadlockAndConserveTotal`
(120 requests, asserts zero 5xx and conservation).

**This one was actually caught live, not just reasoned about.** The first deployed version still
had `transfers.from_wallet_id`/`to_wallet_id` declared as foreign keys to `wallets`. Burst-testing
the deployed URL with 40 concurrent A→B transfers racing 40 concurrent B→A transfers returned
~90% `500`s. Root cause: Postgres takes an implicit `FOR KEY SHARE` lock on each FK-referenced row
*during the INSERT itself*, in column order (`from_wallet_id` then `to_wallet_id`) — not in the
sorted order the explicit `SELECT ... FOR UPDATE` uses. That reintroduced the identical
A↔B deadlock cycle one statement earlier, where the sort order had no effect: transaction 1
FK-locks A then B (from its own insert), transaction 2 FK-locks B then A; each then tries to
escalate to `FOR UPDATE` on the *other* wallet per the sorted order and blocks on a row the other
already holds. Postgres detected the cycle and aborted one side with `deadlock detected`
(`40P01`), surfaced as a generic `500`. Fixed by dropping both foreign keys
(`V2__drop_transfer_wallet_fk.sql`) and relying on the pre-existing application-level
`walletRepository.findById` existence checks instead — full writeup in the README's "Removed
after live testing" section. This is exactly the kind of thing "we reproduce it against your URL"
is meant to catch, and it did.

## 5. Source wallet becomes insufficient under concurrent debits

**What happens:** Every transfer touching a wallet locks that wallet's row with `SELECT ... FOR
UPDATE` before reading its balance. A second transaction trying to touch the same wallet blocks
until the first commits or rolls back — so "check balance, then debit" can never observe a
balance that a concurrent transaction is simultaneously changing.

**Why safe:** Row-level locking serializes all writers to one wallet; there is no interleaving
window between the balance check and the debit.

**Proof:** `WalletRepository.lockForUpdate`, `TransferOverdraftTest`, and indirectly
`TransferConservationTest` (300-request burst never produces a negative balance).

## 6. Database transaction rollback after debit

**What happens:** If anything after the debit throws (it can't, currently, but hypothetically),
Spring's `@Transactional` rolls back the *entire* transaction — the debit, the credit if it ran,
and the transfer-row insert/update all undo together.

**Why safe:** All of it is one transaction. Postgres transactions are atomic by definition —
partial commit isn't a thing.

**Proof:** `@Transactional` on `TransferService.createTransfer`; no manual commit/flush anywhere
in the method that could split the unit of work.

## 7. Application crash mid-transaction

**What happens:** The dropped connection causes Postgres to roll back whatever that connection's
open transaction had done. If it was the idempotency-key winner mid-debit, the whole thing
(including the `INSERT ... PENDING`) unwinds — the key row disappears entirely. Any request still
waiting on that unique key (blocked at its own `INSERT`) then finds no conflict and becomes the
new winner. If it was a *loser* waiting, it simply keeps waiting on a lock/index entry that no
longer conflicts once the crash's rollback completes.

**Why safe:** Postgres treats a lost connection as an implicit `ROLLBACK`. Nothing partially
committed can be "seen" by anyone, ever — MVCC visibility rules guarantee that.

**Proof:** Standard Postgres connection-loss semantics (not something this codebase implements;
it's what makes not needing a saga/compensation framework a legitimate choice here).

## 8. Database connection failure

**What happens:** HikariCP either surfaces a connection-acquisition timeout (pool exhausted /
DB unreachable) as an exception from the JDBC call, or fails a query mid-flight. Either way it
propagates up through `@Transactional`, the transaction rolls back, and `GlobalExceptionHandler`
turns it into a `500` with a correlation id (never a raw stack trace to the caller).

**Why safe:** No code path assumes a DB call always succeeds; the transaction boundary means a
failure at any point undoes everything, it doesn't leave a half-applied transfer.

**Proof:** `GlobalExceptionHandler.handleUnexpected`; `spring.datasource.hikari.connection-timeout`
in `application.yml`.

## 9. Duplicate HTTP requests (client-side retry, proxy replay, etc.)

**What happens:** Indistinguishable from "two concurrent transfers, same idempotency key" (#2)
from the server's point of view — that's the entire point of requiring `idempotency_key` from
the caller. A duplicate with the same key and body replays the original result; a duplicate with
a different body (a buggy retry that regenerated the request) gets a `409` instead of silently
applying.

**Why safe:** Same mechanism as #2/#3; this scenario doesn't need separate handling because
idempotency was designed at the protocol level, not as an HTTP-layer special case.

**Proof:** Same as #2/#3.

## 10. Two application instances processing requests simultaneously

**What happens:** Nothing in the correctness mechanism lives in application memory — no
`synchronized`, no in-process map, no local cache of "keys I've seen." Every guarantee (get-or-
create, idempotency, locking, no-overdraft) is enforced by Postgres constraints and row locks,
which are shared and visible across every connection from every instance identically.

**Why safe:** The correctness argument never mentioned "the JVM" or "this process" anywhere —
it's entirely about what the database serializes. Running 1 instance or 50 behind a load balancer
changes throughput, not correctness.

**Proof:** Re-read `TransferRepository`/`WalletRepository` — there is no `static`, no
`ConcurrentHashMap`, no singleton lock object anywhere in the money path. (Not separately load
tested with two real instances in this exercise, since the invariant is that the mechanism is
DB-level rather than process-level; the same DB-level guarantees are what the two-worker
scheduler and rate-limiter exercises elsewhere in this exercise bank explicitly probe across
instances.)

---

## Debrief Q&A

**Q1. Walk me through A→B and B→A arriving at the same instant.**
Both sort the pair to the same `(firstId, secondId)`. Whichever transaction's `SELECT ... FOR
UPDATE firstId` commits to the lock first proceeds to lock `secondId` next; the other blocks on
`firstId` until the first transaction commits. No cycle can form because both transactions always
request locks in the same order. See scenario #4.

**Q2. Where is idempotency enforced?**
`UNIQUE(transfers.idempotency_key)` in the schema, exploited via `INSERT ... ON CONFLICT (idempotency_key)
DO NOTHING` in `TransferRepository.insertPending`, called from inside the same `@Transactional`
method that does the debit/credit.

**Q3. Why must idempotency uniqueness and the ledger movement be in the same transaction?**
If they were separate transactions, a crash (or even just a slow request) between "key recorded"
and "money moved" would let a concurrent retry either see a committed key with no money moved
(and wrongly treat that as success) or race the movement and double-apply it. One transaction
means the key and the movement are atomically all-or-nothing together — see the README's
"Transaction boundary & idempotency strategy" section.

**Q4. What happens if two requests with the same idempotency key race?**
One wins the `INSERT`, proceeds through locking/debit/credit/completion in its transaction. The
other blocks on the `INSERT` until the winner commits, then reads back the finished row and
returns it — no polling, no second attempt at the insert. See scenario #2.

**Q5. What happens if the same key has a different body?**
`request_hash` mismatch → `409`, no state change. See scenario #3.

**Q6. Why did you choose your locking/atomic update strategy?**
Sorted `SELECT ... FOR UPDATE` on both wallets before reading either balance — chosen over a
sort-order-agnostic atomic conditional `UPDATE` because the latter can credit the destination
before discovering the source can't afford it, when the destination happens to sort first under
a fixed lock order. Locking both rows up front and deciding before mutating either avoids that
partial-apply case entirely. Full reasoning in the README's "Rejected alternatives" section.

**Q7. Why not SERIALIZABLE?**
The specific contention pattern here (concurrent transfers on the same wallet pair) is fully
handled by row-level locks at `READ COMMITTED`. `SERIALIZABLE` would add generic
retry-on-serialization-failure handling for anomaly classes (write skew, phantom reads) this
schema doesn't have, for no benefit here.

**Q8. Why not Redis?**
A distributed lock would be a second source of truth for something the database — already the
system of record — already guarantees atomically via row locks and unique constraints. It adds a
new failure mode (lock service unavailable, lease expiring mid-transfer) without removing any
existing one.

**Q9. How do you guarantee no overdraft?**
The balance check happens after both wallet rows are locked and before either is mutated (see
scenario #5); `wallets.balance_paise` also carries `CHECK (balance_paise >= 0)` as a second,
independent guarantee that would abort the transaction if the application logic ever had a bug.

**Q10. How do you prove conservation?**
`TransferConservationTest` seeds three wallets, fires 300 concurrent transfers among them, and
asserts `sum(balances before) == sum(balances after)`, plus a dedicated 120-request A↔B burst
asserting the same two-wallet total is unchanged. Structurally, conservation holds because every
completed transfer's debit and credit are two `UPDATE`s inside one transaction that already holds
both row locks — nothing else can observe or mutate either balance mid-transfer.

**Q11. Show me a log line for insufficient funds and trace its correlation ID.**
`DomainEvents.builder("transfer.declined.insufficient_funds")` in `TransferService`, fields
`transfer_id`, `from_wallet_id`, `balance_paise`, `amount_paise`. `correlation_id` is attached
automatically by `CorrelationIdFilter` via MDC to every log line for that request, and the same
value is echoed back in the `X-Correlation-Id` response header — grep the logs for that header
value and every line for that request (access log, `transfer.created`, the lock/decline event)
comes back together.

**Q12. What happens if the process crashes during a transfer?**
See scenario #7 — the open transaction (whatever it had done, including the idempotency-key
insert) rolls back as a unit; nothing partial is ever visible to another transaction.

**Q13. What happens across two application instances?**
See scenario #10 — every guarantee is DB-level (constraints, row locks), not process-level, so it
holds identically regardless of instance count.

**Q14. How would you implement reversal/refund?**
Reuse the exact same primitive with roles swapped: `POST /transfers/{id}/reverse` would look up
the original `COMPLETED` transfer, then run the identical sorted-lock → check-balance →
debit/credit → mark-terminal sequence with `from`/`to` swapped, gated by its own
`idempotency_key` inserted the same way (so double-reversal is impossible for the same reason
double-transfer is impossible), and guarded against reversing a transfer that's already been
reversed or was never `COMPLETED`. Not implemented now, per the brief's instruction not to build
R3 scope early — but the domain is already shaped for it: `TransferService` and
`TransferRepository` have no transfer-direction-specific logic baked in, they operate on whatever
`(from, to, amount)` they're given.
