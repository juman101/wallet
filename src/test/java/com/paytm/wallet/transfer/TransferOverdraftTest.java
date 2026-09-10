package com.paytm.wallet.transfer;

import com.paytm.wallet.common.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** TEST 5 from "THE THINGS WE WILL PROBE LIVE": an overdrawing transfer must be cleanly declined. */
class TransferOverdraftTest extends AbstractIntegrationTest {

    @Test
    void transferGreaterThanBalanceIsDeclinedCleanlyWithNoPartialApply() {
        String from = createFundedWallet(100);
        String to = createFundedWallet(50);

        ResponseEntity<String> response = postTransfer(from, to, 200, "overdraft-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"status\":\"DECLINED\"");
        assertThat(response.getBody()).contains("insufficient_funds");

        assertThat(currentBalance(from)).isEqualTo(100);
        assertThat(currentBalance(to)).isEqualTo(50);
    }

    @Test
    void exactBalanceTransferSucceedsDownToZero() {
        String from = createFundedWallet(100);
        String to = createFundedWallet(0);

        ResponseEntity<String> response = postTransfer(from, to, 100, "exact-" + UUID.randomUUID());

        assertThat(response.getBody()).contains("\"status\":\"COMPLETED\"");
        assertThat(currentBalance(from)).isEqualTo(0);
        assertThat(currentBalance(to)).isEqualTo(100);
    }

    private ResponseEntity<String> postTransfer(String from, String to, long amountPaise, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer any-caller");
        String body = """
                {"from":"%s","to":"%s","amount_paise":%d,"idempotency_key":"%s"}
                """.formatted(from, to, amountPaise, idempotencyKey);
        return restTemplate.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
