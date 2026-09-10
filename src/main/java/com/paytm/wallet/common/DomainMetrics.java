package com.paytm.wallet.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters exposed on /actuator/prometheus, on top of the default HTTP
 * request-rate/latency/error metrics Spring Boot Actuator already provides.
 */
@Component
public class DomainMetrics {

    private final Counter walletsCreated;
    private final Counter transfersCreated;
    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter transfersIdempotentReplays;
    private final Counter transfersConflicts;

    public DomainMetrics(MeterRegistry registry) {
        // NOTE: deliberately not "..._created_total" - Micrometer's Prometheus naming
        // convention treats "_created" as OpenMetrics's reserved companion-timestamp suffix and
        // silently strips it (e.g. "wallets_created_total" is exposed on the wire as just
        // "wallets_total"). Found by scraping the live deployment's /actuator/prometheus and
        // diffing against these registration names - not a guess.
        this.walletsCreated = Counter.builder("wallet_creations_total")
                .description("Wallets created (get-or-create winners only)")
                .register(registry);
        this.transfersCreated = Counter.builder("transfer_creations_total")
                .description("Transfer requests that won the idempotency-key insert race")
                .register(registry);
        this.transfersCompleted = Counter.builder("transfers_completed_total")
                .description("Transfers that debited and credited successfully")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("transfers_declined_insufficient_funds_total")
                .description("Transfers cleanly declined for insufficient funds")
                .register(registry);
        this.transfersIdempotentReplays = Counter.builder("transfers_idempotent_replays_total")
                .description("Requests that replayed an already-processed idempotency_key")
                .register(registry);
        this.transfersConflicts = Counter.builder("transfers_conflicts_total")
                .description("Requests rejected because idempotency_key was reused with a different body")
                .register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void transferCreated() {
        transfersCreated.increment();
    }

    public void transferCompleted() {
        transfersCompleted.increment();
    }

    public void transferDeclinedInsufficientFunds() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void transferIdempotentReplay() {
        transfersIdempotentReplays.increment();
    }

    public void transferConflict() {
        transfersConflicts.increment();
    }
}
