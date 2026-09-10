package com.paytm.wallet.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHashUtilTest {

    @Test
    void sameFieldsProduceSameHash() {
        String h1 = RequestHashUtil.sha256("wallet-a", "wallet-b", "1000");
        String h2 = RequestHashUtil.sha256("wallet-a", "wallet-b", "1000");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void differentAmountProducesDifferentHash() {
        String h1 = RequestHashUtil.sha256("wallet-a", "wallet-b", "1000");
        String h2 = RequestHashUtil.sha256("wallet-a", "wallet-b", "2000");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void fieldBoundaryShiftDoesNotCollide() {
        // "ab"+"c" and "a"+"bc" must not hash the same just because concatenation would collide.
        String h1 = RequestHashUtil.sha256("ab", "c");
        String h2 = RequestHashUtil.sha256("a", "bc");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void isHexEncodedSha256() {
        String hash = RequestHashUtil.sha256("x", "y", "z");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
