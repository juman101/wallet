package com.paytm.wallet.wallet;

import com.paytm.wallet.common.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INVARIANT: Two concurrent POST /wallets for the same user yield ONE wallet, not two.
 * Rubric Gate 1.
 */
class WalletConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void fiftyConcurrentGetOrCreateForSameNewUserYieldExactlyOneWallet() throws InterruptedException {
        String userId = "burst-user-" + UUID.randomUUID();
        int concurrency = 50;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<WalletResponse>> futures = IntStream.range(0, concurrency)
                .<Future<WalletResponse>>mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("Authorization", "Bearer " + userId);
                    ResponseEntity<WalletResponse> response = restTemplate.exchange(
                            "/wallets", HttpMethod.POST, new HttpEntity<>(headers), WalletResponse.class);
                    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                    return response.getBody();
                }))
                .collect(Collectors.toList());

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).as("all requests must finish, none hang").isTrue();

        Set<String> distinctWalletIds = futures.stream()
                .map(f -> {
                    try {
                        return f.get().walletId();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toSet());

        assertThat(distinctWalletIds).as("exactly one wallet must be created under a 50-way burst").hasSize(1);

        Integer walletCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE user_id = ?", Integer.class, userId);
        assertThat(walletCount).isEqualTo(1);
    }
}
