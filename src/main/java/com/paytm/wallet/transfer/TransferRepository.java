package com.paytm.wallet.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> ROW_MAPPER = (rs, rowNum) -> new Transfer(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("from_wallet_id")),
            UUID.fromString(rs.getString("to_wallet_id")),
            rs.getLong("amount_paise"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the transfer row as PENDING in the SAME transaction the caller will use to move
     * money in. The UNIQUE(idempotency_key) constraint is what makes "same key -> exactly one
     * transfer" true under concurrency - see the class-level note in TransferService for why
     * this specific ordering (idempotency row first, then lock+move money, same transaction)
     * matters.
     *
     * @return true if this call won the race and inserted the row, false if the key already existed.
     */
    public boolean insertPending(UUID id, UUID fromWalletId, UUID toWalletId, long amountPaise,
                                  String idempotencyKey, String requestHash) {
        int rows = jdbc.update("""
                INSERT INTO transfers
                    (id, from_wallet_id, to_wallet_id, amount_paise, idempotency_key, request_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', now(), now())
                ON CONFLICT (idempotency_key) DO NOTHING
                """, id, fromWalletId, toWalletId, amountPaise, idempotencyKey, requestHash);
        return rows == 1;
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM transfers WHERE idempotency_key = ?", ROW_MAPPER, idempotencyKey)
                .stream().findFirst();
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    public void markCompleted(UUID id) {
        jdbc.update("UPDATE transfers SET status = 'COMPLETED', updated_at = now() WHERE id = ?", id);
    }

    public void markDeclined(UUID id, String reason) {
        jdbc.update("UPDATE transfers SET status = 'DECLINED', failure_reason = ?, updated_at = now() WHERE id = ?",
                reason, id);
    }
}
