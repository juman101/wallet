package com.paytm.wallet.transfer;

import com.paytm.wallet.common.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST 4 (conservation under contention) and TEST 6 (A-&gt;B / B-&gt;A deadlock scenario) from
 * "THE THINGS WE WILL PROBE LIVE".
 */
class TransferConservationTest extends AbstractIntegrationTest {

    @Test
    void manyConcurrentCrossTransfersAmongThreeWalletsConserveTotalAndNeverGoNegative() throws InterruptedException {
        long seedBalance = 1_000_000;
        String a = createFundedWallet(seedBalance);
        String b = createFundedWallet(seedBalance);
        String c = createFundedWallet(seedBalance);
        long totalBefore = seedBalance * 3;
        List<String> wallets = List.of(a, b, c);

        int requestCount = 300;
        int concurrency = 40;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        List<Future<ResponseEntity<String>>> futures = IntStream.range(0, requestCount)
                .<Future<ResponseEntity<String>>>mapToObj(i -> pool.submit(() -> {
                    String from = wallets.get(i % 3);
                    String to = wallets.get((i + 1 + (i / 3) % 2) % 3);
                    long amount = 100 + (i % 17) * 137L;
                    return postTransfer(from, to, amount, "conservation-" + UUID.randomUUID());
                }))
                .collect(Collectors.toList());

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<ResponseEntity<String>> f : futures) {
            ResponseEntity<String> response = get(f);
            assertThat(response.getStatusCode().is5xxServerError())
                    .as("no request may fail with a 5xx: " + response.getBody())
                    .isFalse();
        }

        long totalAfter = currentBalance(a) + currentBalance(b) + currentBalance(c);
        assertThat(totalAfter).as("money must be conserved across the whole burst").isEqualTo(totalBefore);
        assertThat(currentBalance(a)).isGreaterThanOrEqualTo(0);
        assertThat(currentBalance(b)).isGreaterThanOrEqualTo(0);
        assertThat(currentBalance(c)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void concurrentOppositeDirectionTransfersDoNotDeadlockAndConserveTotal() throws InterruptedException {
        long seedBalance = 500_000;
        String a = createFundedWallet(seedBalance);
        String b = createFundedWallet(seedBalance);
        long totalBefore = seedBalance * 2;

        int pairsPerDirection = 60;
        ExecutorService pool = Executors.newFixedThreadPool(2 * pairsPerDirection);

        List<Future<ResponseEntity<String>>> futures = IntStream.range(0, pairsPerDirection)
                .boxed()
                .flatMap(i -> java.util.stream.Stream.of(
                        pool.submit(() -> postTransfer(a, b, 1_000, "deadlock-ab-" + UUID.randomUUID())),
                        pool.submit(() -> postTransfer(b, a, 1_000, "deadlock-ba-" + UUID.randomUUID()))
                ))
                .collect(Collectors.toList());

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("no request should hang forever waiting on a deadlocked lock")
                .isTrue();

        for (Future<ResponseEntity<String>> f : futures) {
            ResponseEntity<String> response = get(f);
            assertThat(response.getStatusCode().is5xxServerError())
                    .as("no deadlock-related 500: " + response.getBody())
                    .isFalse();
        }

        assertThat(currentBalance(a) + currentBalance(b)).isEqualTo(totalBefore);
    }

    private ResponseEntity<String> get(Future<ResponseEntity<String>> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
