package com.paytm.wallet.common;

/** Holds the caller identity (the bearer token value) for the duration of one request. */
public final class AuthContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private AuthContext() {
    }

    static void set(String userId) {
        CURRENT_USER.set(userId);
    }

    public static String currentUserId() {
        return CURRENT_USER.get();
    }

    static void clear() {
        CURRENT_USER.remove();
    }
}
