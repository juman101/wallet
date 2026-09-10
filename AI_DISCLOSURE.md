# AI Disclosure

This implementation was built with an AI coding agent (Claude Code) working from a detailed
engineering brief. This file is honest about the split between what was directed (the candidate
chose the approach, the agent typed it) and what was decided by the agent (an implementation
choice made without the brief mandating that specific answer).

## AI-directed (candidate decided, agent typed)

- Overall layering: controller → service → repository → PostgreSQL, no JPA/Hibernate, no
  microservices, no Kafka/Redis/Kubernetes/CQRS/event sourcing unless a concrete requirement
  demanded it (none did).
- PostgreSQL as sole source of truth; Flyway for migrations, no manually-created schema.
- The core correctness mechanism: idempotency-key uniqueness and the ledger movement committed
  in the **same** database transaction; race-free get-or-create via a unique constraint plus
  `INSERT ... ON CONFLICT DO NOTHING`, never check-then-insert.
- Deterministic (sorted) lock ordering across wallet pairs to prevent A→B/B→A deadlock.
- Money as integer paise everywhere, no floats, no decimal-rupee representation.
- Structured JSON logging with a correlation id on every request, and the specific set of
  domain events to log (`transfer.created`, `transfer.debited`, `transfer.credited`,
  `transfer.completed`, `transfer.declined.insufficient_funds`, `transfer.idempotent_replay`,
  `transfer.conflict`, `wallet.created`).
- Prometheus metrics: HTTP request rate/latency/p99/error-rate plus the specific domain counters
  named in the brief (`transfers_created_total`, `transfers_completed_total`,
  `transfers_declined_insufficient_funds_total`, `transfers_idempotent_replays_total`,
  `transfers_conflicts_total`).
- Docker: multi-stage build, non-root user, `HEALTHCHECK`; `docker compose up` brings up app +
  Postgres in one command.
- The six specific concurrency test scenarios (concurrent get-or-create, idempotency storm,
  same-key-different-body, conservation under contention, overdraft, A↔B deadlock) and the
  one-command burst script with the exact `[PASS]`/`[FAIL]` output format.
- Bearer-token auth kept intentionally minimal (token value = user id; no OAuth/JWT
  infrastructure), per explicit instruction not to over-invest there.
- Consistency-over-availability stance for the money workload.
- Rule against fabricated git authorship; this file's existence and structure.
- Stack (Java 17 / Spring Boot / Maven / PostgreSQL) and project location, chosen by the human in
  this session when asked directly, given prior familiarity with Spring Boot/JPA/Hibernate.

## AI-decided (delegated, agent's implementation choice)

- Between the two rubric-acceptable debit/credit mechanisms (sorted `SELECT ... FOR UPDATE` vs.
  an atomic conditional `UPDATE ... WHERE balance >= amount`), the agent chose sorted
  `SELECT ... FOR UPDATE` and explains why in the README (it avoids a partial-apply pitfall the
  atomic-update approach has under a fixed lock order when the "to" wallet sorts first).
- The request-fingerprint formula for same-key/different-body detection:
  `SHA-256(from|to|amount_paise)` with a literal `|` delimiter between fields.
- Adding a **test-only** `POST /wallets/{id}/deposit` faucet. Nothing in the brief's minimum API
  can fund a wallet at all, so without this endpoint none of the graded invariants (conservation,
  no-overdraft, idempotency storm) can be exercised against a live deployment. It is explicitly
  called out as out-of-scope of the graded surface in the README and code comments, not quietly
  smuggled in as a "real" feature.
- Exact HTTP status codes for each outcome (201 for a created/replayed transfer including
  declines, 409 for idempotency conflicts, 404 for unknown wallets/transfers, 400 for validation
  failures, 401 for a missing/malformed bearer token).
- Package layout, class/method names, and JSON field naming (`wallet_id`, `balance_paise`,
  `transfer_id`, `idempotency_key`, etc.).
- Specific library choices within the directed constraints: plain Spring JDBC (not JPA) for
  explicit control over the exact SQL used for locking and atomic updates;
  `logstash-logback-encoder` for JSON log output; Testcontainers + a real Postgres for the
  concurrency integration tests (in-memory/H2 would not exercise real row-locking behavior).
- `burst-test.sh` implemented in plain bash + curl + grep/sed rather than requiring `jq`, since
  the local dev environment didn't have `jq` installed and the brief allows "bash + curl."

## What the agent did **not** decide unsupervised

No design decision in the "Concurrency design," "Recommended transaction model," or "Hard
correctness invariants" sections of the brief was left to the agent's judgment without an
explicit instruction or a stated, defensible rationale recorded above or in the README's
"Rejected alternatives" section.
