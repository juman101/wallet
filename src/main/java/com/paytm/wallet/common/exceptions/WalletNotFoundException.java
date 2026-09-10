package com.paytm.wallet.common.exceptions;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(String walletId) {
        super("Wallet not found: " + walletId);
    }
}
