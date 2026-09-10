package com.paytm.wallet.wallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private static final RowMapper<Wallet> ROW_MAPPER = (rs, rowNum) -> new Wallet(
            UUID.fromString(rs.getString("id")),
            rs.getString("user_id"),
            rs.getLong("balance_paise"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Race-free get-or-create: relies entirely on the UNIQUE(user_id) constraint. Under a
     * concurrent burst, exactly one INSERT wins; every other concurrent INSERT blocks on the
     * unique index until the winner commits, then sees the conflict and no-ops (rows == 0).
     * There is no read-then-insert gap for a race to fit into.
     *
     * @return true if this call created the wallet (won the race), false if it already existed.
     */
    public boolean insertIfAbsent(UUID id, String userId) {
        int rows = jdbc.update("""
                INSERT INTO wallets (id, user_id, balance_paise, created_at, updated_at)
                VALUES (?, ?, 0, now(), now())
                ON CONFLICT (user_id) DO NOTHING
                """, id, userId);
        return rows == 1;
    }

    public Optional<Wallet> findByUserId(String userId) {
        return jdbc.query("SELECT * FROM wallets WHERE user_id = ?", ROW_MAPPER, userId)
                .stream().findFirst();
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbc.query("SELECT * FROM wallets WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Locks the row for the duration of the enclosing transaction. Callers MUST invoke this on
     * both wallets involved in a transfer in a deterministic order (sorted by id) to make lock
     * acquisition order consistent across all transactions and avoid deadlock - see
     * TransferService.
     */
    public Optional<Wallet> lockForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM wallets WHERE id = ? FOR UPDATE", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Unconditional - only safe to call after lockForUpdate has already verified sufficient
     * balance while holding the row lock.
     */
    public void adjustBalance(UUID id, long deltaPaise) {
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = now() WHERE id = ?",
                deltaPaise, id);
    }
}
