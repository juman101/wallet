package com.paytm.wallet.wallet;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WalletResponse(
        @JsonProperty("wallet_id") String walletId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("balance_paise") long balancePaise
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id().toString(), wallet.userId(), wallet.balancePaise());
    }
}
