package com.paytm.wallet.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAndValidationTest extends AbstractIntegrationTest {

    @Test
    void missingAuthorizationHeaderIs401OnWallets() {
        ResponseEntity<String> response = restTemplate.postForEntity("/wallets", HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingAuthorizationHeaderIs401OnTransfers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"from\":\"" + UUID.randomUUID() + "\",\"to\":\"" + UUID.randomUUID()
                + "\",\"amount_paise\":100,\"idempotency_key\":\"k\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownWalletReturns404() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer someone");
        ResponseEntity<String> response = restTemplate.exchange(
                "/wallets/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        String from = createFundedWallet(1000);
        String to = createFundedWallet(0);
        ResponseEntity<String> response = postTransfer(from, to, 0, "zero-amount-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameFromAndToIsRejected() {
        String wallet = createFundedWallet(1000);
        ResponseEntity<String> response = postTransfer(wallet, wallet, 100, "self-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        String from = createFundedWallet(1000);
        String to = createFundedWallet(0);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer any-caller");
        String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount_paise\":100}";
        ResponseEntity<String> response = restTemplate.exchange(
                "/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transferToNonexistentWalletReturns404() {
        String from = createFundedWallet(1000);
        ResponseEntity<String> response = postTransfer(from, UUID.randomUUID().toString(), 100,
                "no-dest-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
