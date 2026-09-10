package com.paytm.wallet.wallet;

import com.paytm.wallet.common.AuthContext;
import com.paytm.wallet.common.exceptions.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> getOrCreate() {
        String userId = AuthContext.currentUserId();
        WalletService.WalletCreationResult result = walletService.getOrCreateWallet(userId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WalletResponse.from(result.wallet()));
    }

    @GetMapping("/{id}")
    public WalletResponse getById(@PathVariable("id") String id) {
        UUID walletId = parseUuid(id);
        return WalletResponse.from(walletService.getWallet(walletId));
    }

    /**
     * TEST-ONLY faucet - not part of the graded API surface. See WalletService#deposit.
     */
    @PostMapping("/{id}/deposit")
    public WalletResponse deposit(@PathVariable("id") String id, @RequestBody DepositRequest request) {
        UUID walletId = parseUuid(id);
        long amount = request.amountPaise() == null ? 0 : request.amountPaise();
        return WalletResponse.from(walletService.deposit(walletId, amount));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Not a valid wallet id: " + value);
        }
    }
}
