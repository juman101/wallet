package com.paytm.wallet.transfer;

import com.paytm.wallet.common.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST 2 (idempotency storm) and TEST 3 (same key, different body) from the exercise's
 * "THE THINGS WE WILL PROBE LIVE" section.
 */
class TransferIdempotencyTest extends AbstractIntegrationTest {

    @Test
    void thirtyConcurrentRetriesWithSameKeyAndBodyProduceExactlyOneDebitAndCredit() throws InterruptedException {
        String from = createFundedWallet(100_000);
        String to = createFundedWallet(0);
        String idempotencyKey = "storm-" + UUID.randomUUID();
        long amount = 1_000;

        int concurrency = 30;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<ResponseEntity<String>>> futures = IntStream.range(0, concurrency)
                .<Future<ResponseEntity<String>>>mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return postTransfer(from, to, amount, idempotencyKey);
                }))
                .collect(Collectors.toList());

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).as("all requests must finish, none hang").isTrue();

        Set<String> distinctBodies = futures.stream().map(f -> {
            try {
                ResponseEntity<String> r = f.get();
                assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
                return r.getBody();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toSet());

        assertThat(distinctBodies).as("every concurrent retry must return the identical result").hasSize(1);

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        assertThat(transferCount).isEqualTo(1);

        assertThat(currentBalance(from)).isEqualTo(100_000 - amount);
        assertThat(currentBalance(to)).isEqualTo(amount);
    }

    @Test
    void sameKeyWithDifferentBodyReturns409AndDoesNotDoubleApply() {
        String from = createFundedWallet(100_000);
        String to = createFundedWallet(0);
        String idempotencyKey = "conflict-" + UUID.randomUUID();

        ResponseEntity<String> first = postTransfer(from, to, 1_000, idempotencyKey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = postTransfer(from, to, 2_000, idempotencyKey);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(currentBalance(from)).isEqualTo(100_000 - 1_000);
        assertThat(currentBalance(to)).isEqualTo(1_000);
    }

    @Test
    void retryAfterCompletionReturnsOriginalResult() {
        String from = createFundedWallet(50_000);
        String to = createFundedWallet(0);
        String idempotencyKey = "retry-" + UUID.randomUUID();

        ResponseEntity<String> first = postTransfer(from, to, 5_000, idempotencyKey);
        ResponseEntity<String> retry = postTransfer(from, to, 5_000, idempotencyKey);

        assertThat(retry.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(currentBalance(from)).isEqualTo(50_000 - 5_000);
        assertThat(currentBalance(to)).isEqualTo(5_000);
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
