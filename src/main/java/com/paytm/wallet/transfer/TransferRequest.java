package com.paytm.wallet.transfer;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransferRequest(
        String from,
        String to,
        @JsonProperty("amount_paise") Long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey
) {
}
