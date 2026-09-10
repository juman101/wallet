package com.paytm.wallet.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        @JsonProperty("transfer_id") String transferId,
        @JsonProperty("from_wallet_id") String fromWalletId,
        @JsonProperty("to_wallet_id") String toWalletId,
        @JsonProperty("amount_paise") long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String status,
        @JsonProperty("failure_reason") String failureReason
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id().toString(),
                transfer.fromWalletId().toString(),
                transfer.toWalletId().toString(),
                transfer.amountPaise(),
                transfer.idempotencyKey(),
                transfer.status().name(),
                transfer.failureReason()
        );
    }
}
