-- The foreign keys from transfers to wallets were causing exactly the deadlock the sorted
-- SELECT ... FOR UPDATE lock order was supposed to prevent: PostgreSQL takes an implicit
-- FOR KEY SHARE lock on each referenced wallet row during the transfers INSERT, in
-- (from_wallet_id, to_wallet_id) column order - NOT in the sorted (ascending wallet id) order
-- TransferService uses for its own explicit locking. Under concurrent opposite-direction
-- transfers (A->B racing B->A), one transaction's FK-check lock on A conflicts with the other's
-- later FOR UPDATE escalation on A, and vice versa on B - a genuine cross-transaction wait
-- cycle, reproduced live as a burst of 500s under an A<->B contention test. See
-- ADVERSARIAL_REVIEW.md for the full diagnosis.
--
-- Referential integrity is preserved at the application level instead: TransferService already
-- validates both wallets exist (walletRepository.findById) before ever attempting the insert, in
-- the same transaction, before any lock is taken. There is no wallet-deletion feature, so there
-- is no concurrent-delete window for a missing FK to protect against here.
ALTER TABLE transfers DROP CONSTRAINT IF EXISTS transfers_from_wallet_id_fkey;
ALTER TABLE transfers DROP CONSTRAINT IF EXISTS transfers_to_wallet_id_fkey;
