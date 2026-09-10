package com.paytm.wallet.transfer;

import com.paytm.wallet.common.DomainEvents;
import com.paytm.wallet.common.DomainMetrics;
import com.paytm.wallet.common.RequestHashUtil;
import com.paytm.wallet.common.exceptions.IdempotencyConflictException;
import com.paytm.wallet.common.exceptions.InvalidRequestException;
import com.paytm.wallet.common.exceptions.TransferNotFoundException;
import com.paytm.wallet.common.exceptions.WalletNotFoundException;
import com.paytm.wallet.wallet.Wallet;
import com.paytm.wallet.wallet.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The whole exercise lives in {@link #createTransfer}. Two correctness mechanisms, both
 * database-enforced, both inside a single transaction:
 *
 * <p><b>1. Idempotency.</b> UNIQUE(idempotency_key) + {@code INSERT ... ON CONFLICT DO NOTHING}.
 * Postgres blocks a conflicting INSERT on an uncommitted duplicate key until the first
 * transaction resolves (commit or rollback) - see
 * https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT. That means by the
 * time a "loser" request reaches the SELECT below, the winner's outcome (COMPLETED/DECLINED) is
 * already final and committed; there is no polling, no separate pre-check transaction, and no
 * TOCTOU gap. If the winner's transaction rolls back instead, its INSERT is undone too, so the
 * next contender sees no conflict and becomes the new winner. This is why the idempotency-key
 * insert and the ledger movement below MUST be the same transaction: if they were split into
 * two transactions, a crash between them (or a concurrent request reading the committed key
 * before the movement transaction commits) could hand back a "success" that never got its money
 * moved, or move money twice for one key.
 *
 * <p><b>2. Conservation / no-overdraft / deadlock-freedom.</b> {@code SELECT ... FOR UPDATE} on
 * both wallets, always acquired in ascending wallet-id order regardless of transfer direction.
 * Locking both rows before reading either balance is what makes "check balance, then debit and
 * credit" safe to do in application code - normally a read-then-write race, but not here because
 * no other transaction can be mid-flight on either row while we hold both locks. The
 * deterministic lock order is what prevents the classic A-&gt;B / B-&gt;A deadlock: without it,
 * transaction 1 could lock A then wait on B while transaction 2 holds B and waits on A. We
 * rejected a single atomic {@code UPDATE ... WHERE balance >= amount} (also correct) because,
 * done in a fixed wallet-id lock order, it can credit the "to" wallet before discovering the
 * "from" wallet has insufficient funds when "to" happens to sort first - a partial apply. Locking
 * both rows up front and deciding before mutating either avoids that entirely.
 */
@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final DomainMetrics metrics;

    public TransferService(TransferRepository transferRepository, WalletRepository walletRepository,
                            DomainMetrics metrics) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.metrics = metrics;
    }

    @Transactional
    public Transfer createTransfer(TransferRequest request) {
        UUID fromId = parseWalletId(request.from(), "from");
        UUID toId = parseWalletId(request.to(), "to");
        long amount = request.amountPaise() == null ? 0 : request.amountPaise();
        String idempotencyKey = request.idempotencyKey();

        if (amount <= 0) {
            throw new InvalidRequestException("amount_paise must be a positive integer");
        }
        if (fromId.equals(toId)) {
            throw new InvalidRequestException("from and to must be different wallets");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("idempotency_key is required");
        }

        walletRepository.findById(fromId).orElseThrow(() -> new WalletNotFoundException(fromId.toString()));
        walletRepository.findById(toId).orElseThrow(() -> new WalletNotFoundException(toId.toString()));

        String requestHash = RequestHashUtil.sha256(fromId.toString(), toId.toString(), Long.toString(amount));
        UUID transferId = UUID.randomUUID();

        boolean won = transferRepository.insertPending(transferId, fromId, toId, amount, idempotencyKey, requestHash);

        if (!won) {
            Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency_key insert conflicted but no row is visible - the other transaction " +
                                    "must have rolled back concurrently; safe to retry the request"));

            if (!existing.requestHash().equals(requestHash)) {
                metrics.transferConflict();
                DomainEvents.builder("transfer.conflict")
                        .field("idempotency_key", idempotencyKey)
                        .field("existing_transfer_id", existing.id())
                        .emit();
                throw new IdempotencyConflictException(idempotencyKey);
            }

            metrics.transferIdempotentReplay();
            DomainEvents.builder("transfer.idempotent_replay")
                    .field("transfer_id", existing.id())
                    .field("idempotency_key", idempotencyKey)
                    .field("status", existing.status())
                    .emit();
            return existing;
        }

        metrics.transferCreated();
        DomainEvents.builder("transfer.created")
                .field("transfer_id", transferId)
                .field("from_wallet_id", fromId)
                .field("to_wallet_id", toId)
                .field("amount_paise", amount)
                .field("idempotency_key", idempotencyKey)
                .emit();

        boolean fromIsFirst = fromId.compareTo(toId) < 0;
        UUID firstLockId = fromIsFirst ? fromId : toId;
        UUID secondLockId = fromIsFirst ? toId : fromId;

        Wallet firstLocked = walletRepository.lockForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalStateException("wallet disappeared between validation and lock: " + firstLockId));
        Wallet secondLocked = walletRepository.lockForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalStateException("wallet disappeared between validation and lock: " + secondLockId));

        Wallet fromWallet = fromIsFirst ? firstLocked : secondLocked;

        if (fromWallet.balancePaise() < amount) {
            transferRepository.markDeclined(transferId, "insufficient_funds");
            metrics.transferDeclinedInsufficientFunds();
            DomainEvents.builder("transfer.declined.insufficient_funds")
                    .field("transfer_id", transferId)
                    .field("from_wallet_id", fromId)
                    .field("balance_paise", fromWallet.balancePaise())
                    .field("amount_paise", amount)
                    .emit();
            return transferRepository.findById(transferId).orElseThrow();
        }

        walletRepository.adjustBalance(fromId, -amount);
        DomainEvents.builder("transfer.debited")
                .field("transfer_id", transferId)
                .field("wallet_id", fromId)
                .field("amount_paise", amount)
                .emit();

        walletRepository.adjustBalance(toId, amount);
        DomainEvents.builder("transfer.credited")
                .field("transfer_id", transferId)
                .field("wallet_id", toId)
                .field("amount_paise", amount)
                .emit();

        transferRepository.markCompleted(transferId);
        metrics.transferCompleted();
        DomainEvents.builder("transfer.completed")
                .field("transfer_id", transferId)
                .emit();

        return transferRepository.findById(transferId).orElseThrow();
    }

    public Transfer getTransfer(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId.toString()));
    }

    private UUID parseWalletId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + " is not a valid wallet id: " + raw);
        }
    }
}
