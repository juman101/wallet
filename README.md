# Wallet & P2P Transfer — Paytm PML R2

A small wallet service with peer-to-peer transfers that stays correct under concurrency and
failure. Java 17, Spring Boot 3, plain JDBC (no JPA/ORM) against PostgreSQL. UI is not part of
this exercise; there is none.

## API

| Method | Path                        | Purpose                                            |
|--------|-----------------------------|-----------------------------------------------------|
| POST   | `/wallets`                  | Get-or-create a wallet for the caller               |
| GET    | `/wallets/{id}`              | Current balance                                     |
| POST   | `/wallets/{id}/deposit`      | **Test-only faucet** — see below                    |
| POST   | `/transfers`                | Move money between two wallets                       |
| GET    | `/transfers/{id}`            | Transfer status                                      |

Auth: `Authorization: Bearer <token>` — the token value *is* the user id. No JWT, no sessions,
no OAuth. The exercise explicitly says not to over-invest in auth.

Money is `amount_paise`, a signed 64-bit integer, everywhere — request bodies, responses, the
database column type (`BIGINT`). There is no float or decimal-rupee representation anywhere in
the money path.

### Why there's a `POST /wallets/{id}/deposit`

The exercise's minimum API has no way to put money into a wallet at all — every wallet is born
at 0 and the only mutation is a P2P transfer. Without *some* way to fund a wallet there is no way
to exercise conservation, no-overdraft, or the idempotency storm against a live deployment. This
endpoint exists solely to seed balances for `burst-test.sh` and the evaluator's live probes. It
is deliberately **not** idempotent, **not** graded, and not part of the four invariants below —
it is a test faucet, not a product feature. If this is considered out of scope, everything else
in this write-up still holds without it; it would just be untestable from outside the database.

## Data model

```sql
wallets (
  id UUID PK,
  user_id VARCHAR UNIQUE NOT NULL,       -- race-free get-or-create pivots on this constraint
  balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0),
  created_at, updated_at
)

transfers (
  id UUID PK,
  from_wallet_id UUID NOT NULL,  -- deliberately NOT a FK to wallets - see below
  to_wallet_id   UUID NOT NULL,
  amount_paise   BIGINT NOT NULL CHECK (amount_paise > 0),
  idempotency_key VARCHAR UNIQUE NOT NULL,   -- exactly-once pivots on this constraint
  request_hash    VARCHAR NOT NULL,           -- fingerprint of (from,to,amount) for 409 detection
  status VARCHAR CHECK (status IN ('PENDING','COMPLETED','DECLINED','FAILED')),
  failure_reason VARCHAR,
  created_at, updated_at,
  CHECK (from_wallet_id <> to_wallet_id)
)
```

Both "exactly-once" guarantees in this system (wallet creation, transfer idempotency) come from
a `UNIQUE` constraint plus `INSERT ... ON CONFLICT (...) DO NOTHING`, not from application code
checking-then-inserting. See `WalletRepository.insertIfAbsent` and
`TransferRepository.insertPending`.

## Transaction boundary & idempotency strategy

`TransferService.createTransfer` is **one** `@Transactional` method, `READ COMMITTED` (the
Spring Boot / Postgres default — no need for `SERIALIZABLE`, see "Rejected alternatives"). One
transaction does all of:

1. `INSERT INTO transfers (...) VALUES (..., 'PENDING') ON CONFLICT (idempotency_key) DO NOTHING`
2. If it won the insert: lock both wallets, check balance, debit/credit, mark the transfer
   `COMPLETED` or `DECLINED` — **same transaction, same commit**.
3. If it lost the insert: read back the existing row and decide replay vs `409`.

The idempotency-key uniqueness and the ledger movement are committed together on purpose. If
they were two transactions, a crash between them — or a concurrent reader observing the
committed key before the money-moving transaction commits — could hand back "success" for money
that was never moved, or move money twice for the same key. Putting them in one transaction
means there is no window where the key exists but the movement doesn't, or vice versa.

**Why the "loser" doesn't need to poll or retry-wait:** Postgres blocks a conflicting `INSERT`
against an uncommitted duplicate unique key until the first transaction resolves (commits or
rolls back) — this is documented `ON CONFLICT` behavior, not something we implemented. So by the
time a losing request reaches step 3's `SELECT`, the winner's outcome is already final. If the
winner instead rolled back (crash, exception), its `INSERT` is undone too, so the next contender
sees no conflict at all and simply becomes the new winner. Idempotency is self-healing across a
crash without any extra recovery code.

Same-key/different-body detection: `request_hash = SHA-256(from|to|amount_paise)`, computed
before the insert and compared on replay. We hash rather than compare raw JSON because
raw-string comparison is brittle to field order and whitespace that mean nothing semantically.

## Concurrency strategy & deadlock prevention

For the actual money movement, once we know we're the transfer's winner:

```
firstId, secondId = sort(fromId, toId)     -- always ascending, regardless of transfer direction
SELECT * FROM wallets WHERE id = firstId  FOR UPDATE
SELECT * FROM wallets WHERE id = secondId FOR UPDATE
-- both rows are now locked; check the "from" wallet's balance
if from.balance < amount: mark DECLINED, commit (no wallet row touched)
else: UPDATE both balances, mark COMPLETED, commit
```

Two things this buys:

- **Deadlock freedom.** Every transaction touching wallets X and Y locks them in the same
  ascending order, so two opposite-direction transfers (A→B racing B→A) can never form a
  wait-cycle — one strictly acquires the first lock before the second one gets a chance to. This
  is what `TransferConservationTest.concurrentOppositeDirectionTransfersDoNotDeadlockAndConserveTotal`
  exercises: 60 A→B and 60 B→A transfers fired concurrently, asserted to produce zero 5xx.
- **No partial apply.** Both rows are locked *before* either balance is read or written, so the
  balance check and both mutations happen atomically from every other transaction's point of
  view — nothing else can be mid-flight on either wallet while we hold both locks.

### Rejected alternative: plain atomic `UPDATE ... WHERE balance >= amount`, unsorted

This is the other commonly-correct mechanism (`UPDATE wallets SET balance = balance - :amt WHERE
id = :id AND balance >= :amt`, checking rows-affected), and it's simpler when the two writes are
independent. It doesn't compose cleanly here, though: if you run debit-then-credit in a *fixed
wallet-id* lock order to avoid deadlock, and the "to" wallet happens to sort before the "from"
wallet, you'd credit the destination before you've even checked whether the source can afford
it — a partial apply if the debit then fails. You can work around this (buffer the credit until
after a successful debit, do the debit first regardless of sort order and accept a different
deadlock argument, etc.) but every workaround ends up re-deriving "lock both rows, decide, then
mutate" in a more roundabout way. Locking with `SELECT ... FOR UPDATE` up front says the same
thing directly, so that's what's implemented.

### Rejected alternative: `SERIALIZABLE` isolation everywhere

Would also be correct, but it's solving a problem we don't have here: the specific two-row
contention pattern (transfers touching the same wallet pair) is fully handled by row-level locks
in `READ COMMITTED`. `SERIALIZABLE` adds retry-on-serialization-failure handling for anomalies
(write skew, phantom reads) that don't arise in this schema, at the cost of the application
needing a generic retry loop around every transaction. Not needed, not used.

### Rejected alternative: Redis / distributed locks, a queue, app-level `synchronized`

All introduce a second source of truth for something Postgres already atomically guarantees. A
distributed lock adds a new failure mode (lock service down, lease expiry mid-transfer) for the
same property `SELECT ... FOR UPDATE` gets for free from the database that's already the system
of record. `synchronized`/in-process locks don't survive a second instance — see the debrief
answer on "what happens across two application instances?" below.

### Removed after live testing: FK from `transfers` to `wallets`

This wasn't a design choice made up front - it was the schema's first version, and it caused a
real, reproduced bug. `V1__init.sql` originally declared `from_wallet_id`/`to_wallet_id UUID NOT
NULL REFERENCES wallets(id)`. Deployed and burst-tested with 40 concurrent A→B transfers racing
40 concurrent B→A transfers, ~90% came back `500`.

Root cause: PostgreSQL takes an implicit `FOR KEY SHARE` lock on every row a foreign key
references, taken *during the INSERT itself*, in column declaration order
(`from_wallet_id` then `to_wallet_id`) - not in the sorted order `TransferService` uses for its
own explicit `SELECT ... FOR UPDATE`. So an A→B transfer's `INSERT INTO transfers` FK-locks A
then B; a concurrent B→A transfer's insert FK-locks B then A. Each transaction then tries to
escalate its FK-share lock to `FOR UPDATE` on the *other* wallet first (per the sorted order),
and each is now waiting on a row the other transaction holds - the exact deadlock cycle the
sorted lock order was supposed to prevent, just relocated one statement earlier where the sort
order isn't in effect. Postgres detects it, aborts one side with `deadlock detected` (`40P01`),
and it surfaces to the caller as a `500`.

Fix (`V2__drop_transfer_wallet_fk.sql`): drop both foreign keys. Referential integrity is
enforced at the application layer instead - `TransferService` already calls
`walletRepository.findById` for both wallets before ever attempting the insert, in the same
transaction. There's no wallet-deletion feature, so there's no concurrent-delete window an FK
would have been the only thing protecting against. This is a known, documented PostgreSQL
behavior (FK checks lock the referenced row), not a bug in Postgres - the bug was assuming a
schema-level constraint couldn't have a concurrency side effect of its own.

### Rejected alternative: idempotency via in-memory map / Redis-only check

Fails across restarts and across instances, and reintroduces exactly the TOCTOU gap section 2
above about "why the same transaction" is meant to close: a separate pre-check (in memory, in
Redis, in a prior transaction) can observe "not seen yet," let two racing requests both proceed,
and only then have one of them fail on a later DB constraint after already doing partial work.
Postgres's own MVCC visibility rules already close this window for free — no separate service to
run, no separate failure mode to reason about.

## Conservation / no-overdraft guarantee

- Conservation: every completed transfer's debit and credit are two `UPDATE`s inside one
  transaction that also locked both rows first — nothing else can observe or mutate either
  balance mid-transfer, so the total across the two wallets is invariant.
- No-overdraft: the balance check happens *after* both locks are held and *before* either
  `UPDATE` runs, so a debit that would overdraw is caught before any mutation — the transfer is
  marked `DECLINED` and committed with zero balance changes. `wallets.balance_paise` also carries
  a `CHECK (balance_paise >= 0)` constraint as defense in depth (it should never fire given the
  application logic above, but if it ever did, the transaction would abort rather than silently
  produce a negative balance).

## Consistency vs availability

This is a money workload: we chose **consistency over availability**. A transfer request may
block (waiting on a wallet-row lock behind another in-flight transfer on the same wallet) or be
declined, but it will never return a "success" that isn't backed by a committed, conserved
ledger movement, and it will never silently drop or duplicate money to keep the request path
fast. Under sustained contention on a single hot wallet, request latency for that wallet degrades
before correctness would ever degrade — that's the trade we're making explicitly, not a side
effect we didn't notice.

## Observability

- **Logs**: structured JSON (`logstash-logback-encoder`) to stdout. Every request gets a
  correlation id (`X-Correlation-Id` — reused from the caller if supplied, generated otherwise,
  echoed back in the response header) threaded through SLF4J's MDC, so every log line for that
  request — HTTP access log included — carries it.
- **Domain events**, one structured log line each: `wallet.created`, `wallet.test_deposit`,
  `transfer.created`, `transfer.debited`, `transfer.credited`, `transfer.completed`,
  `transfer.declined.insufficient_funds`, `transfer.idempotent_replay`, `transfer.conflict`.
  See `DomainEvents` / `TransferService`.
- **Metrics**: `/actuator/prometheus`. Standard HTTP metrics (request rate, latency incl. p99,
  error rate) come from Micrometer's built-in instrumentation of every Spring MVC request.
  Domain counters (`transfer_creations_total`, `transfers_completed_total`,
  `transfers_declined_insufficient_funds_total`, `transfers_idempotent_replays_total`,
  `transfers_conflicts_total`, `wallet_creations_total`) are hand-registered in `DomainMetrics`.
  (Not `..._created_total` - confirmed by scraping the live deployment that Micrometer's
  Prometheus naming convention silently strips a `_created` segment as an OpenMetrics reserved
  word, e.g. `wallets_created_total` was actually exposed as just `wallets_total`. Renamed to
  avoid the collision once found.)
- **Health**: `/actuator/health` (used by the Docker `HEALTHCHECK` and compose's
  `depends_on: condition: service_healthy`).

## Running locally

```bash
docker compose up --build
# app on http://localhost:8080, Postgres on localhost:5432
./burst-test.sh
```

Fresh checkout to running burst test is exactly those two commands. Flyway runs the schema
migration automatically on application startup — no manual DB setup.

### Tests

```bash
./mvnw test
```

Unit tests (`RequestHashUtilTest`) need nothing. Everything else extends
`AbstractIntegrationTest`, which spins up a real Postgres via Testcontainers and drives the
actual HTTP API with `TestRestTemplate` — these are the concurrency tests (50-way wallet burst,
30-way idempotency storm, 300-request conservation burst, 120-request A↔B deadlock burst,
overdraft, validation, auth). Requires Docker running locally.

## Deployment

Built for a free host + free managed Postgres (Render / Railway / Fly.io / Koyeb). The app reads
`DB_URL` / `DB_USER` / `DB_PASSWORD` / `PORT` from the environment (see `application.yml`) —
point those at the managed Postgres instance and deploy the image built from the `Dockerfile`.
`/actuator/health` and `/actuator/prometheus` are both exposed for the host's health checks and
for anyone scraping metrics. Expected cost: ₹0 (free tier compute + free tier managed Postgres).

## AI disclosure

See `AI_DISCLOSURE.md`.

## Rejected alternatives, summarized

| Alternative | Rejected because |
|---|---|
| `SELECT` then `INSERT` (no unique constraint) for get-or-create | Textbook TOCTOU race; two concurrent callers both pass the `SELECT` and both `INSERT` |
| `SERIALIZABLE` isolation everywhere | Stronger than the actual contention pattern requires; forces a generic retry loop for anomalies this schema doesn't have |
| Unsorted `SELECT ... FOR UPDATE` / unsorted atomic `UPDATE` | Deadlocks under concurrent opposite-direction transfers |
| FK from `transfers` to `wallets` | Reproduced live: implicit FK-check row locks on INSERT are taken in column order, not our sorted order, reintroducing the A↔B deadlock one statement earlier |
| App-level locks / `synchronized` | Doesn't hold across process restarts or multiple instances |
| Redis / distributed lock for transfer mutual exclusion | New failure mode for a property Postgres row locks already give for free |
| Idempotency via in-memory map or Redis-only pre-check | Doesn't survive restart/instances; reopens the TOCTOU gap a same-transaction DB constraint closes |
| Kafka / event sourcing / CQRS | This is a small synchronous transactional workload; no requirement drives that complexity |
