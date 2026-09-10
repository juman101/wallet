package com.paytm.wallet.common;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("wallet_test")
            .withUsername("wallet")
            .withPassword("wallet");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Concurrency tests fire dozens of simultaneous requests - give the pool enough
        // connections that we're testing DB-level correctness, not pool starvation.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TestRestTemplate restTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    /** Creates a fresh wallet and seeds it with a starting balance via the test-only deposit endpoint. */
    protected String createFundedWallet(long balancePaise) {
        String userId = "test-user-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + userId);
        ResponseEntity<String> response = restTemplate.exchange(
                "/wallets", HttpMethod.POST, new HttpEntity<>(headers), String.class);
        String walletId = extractField(response.getBody(), "wallet_id");

        if (balancePaise > 0) {
            HttpHeaders depositHeaders = new HttpHeaders();
            depositHeaders.setContentType(MediaType.APPLICATION_JSON);
            depositHeaders.set("Authorization", "Bearer " + userId);
            String depositBody = "{\"amount_paise\":" + balancePaise + "}";
            ResponseEntity<String> depositResponse = restTemplate.exchange(
                    "/wallets/" + walletId + "/deposit", HttpMethod.POST,
                    new HttpEntity<>(depositBody, depositHeaders), String.class);
            if (!depositResponse.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("failed to seed wallet balance: " + depositResponse.getBody());
            }
        }
        return walletId;
    }

    protected long currentBalance(String walletId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance_paise FROM wallets WHERE id = ?::uuid", Long.class, walletId);
    }

    private String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("field " + field + " not found in " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
