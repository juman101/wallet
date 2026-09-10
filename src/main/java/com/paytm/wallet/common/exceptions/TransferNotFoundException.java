package com.paytm.wallet.common.exceptions;

public class TransferNotFoundException extends RuntimeException {
    public TransferNotFoundException(String transferId) {
        super("Transfer not found: " + transferId);
    }
}
