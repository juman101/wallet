package com.paytm.wallet.transfer;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        String idempotencyKey,
        String requestHash,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
