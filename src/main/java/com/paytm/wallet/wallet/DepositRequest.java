package com.paytm.wallet.wallet;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DepositRequest(@JsonProperty("amount_paise") Long amountPaise) {
}
