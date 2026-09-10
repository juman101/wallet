-- Wallets: one row per user, race-free get-or-create is enforced by the UNIQUE(user_id)
-- constraint below, exploited via INSERT ... ON CONFLICT (user_id) DO NOTHING.
CREATE TABLE wallets (
    id              UUID PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    balance_paise   BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_paise >= 0)
);

-- Transfers: the idempotency_key uniqueness is enforced by the DB and is committed in the
-- SAME transaction as the ledger movement (see TransferService). status transitions:
-- PENDING -> COMPLETED | DECLINED | FAILED
CREATE TABLE transfers (
    id                  UUID PRIMARY KEY,
    from_wallet_id      UUID NOT NULL REFERENCES wallets (id),
    to_wallet_id        UUID NOT NULL REFERENCES wallets (id),
    amount_paise        BIGINT NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    failure_reason      VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED'))
);

CREATE INDEX idx_transfers_from_wallet_id ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet_id ON transfers (to_wallet_id);
