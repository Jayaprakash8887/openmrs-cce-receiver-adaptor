package org.openphc.cce.receiver.beckn;

/** The error codes of a {@code Nack} (docs/beckn-integration.md §3.4). */
public final class BecknCodes {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    public static final String REFERRAL_REJECTED = "REFERRAL_REJECTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private BecknCodes() {
    }
}
