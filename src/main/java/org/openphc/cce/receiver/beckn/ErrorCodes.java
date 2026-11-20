package org.openphc.cce.receiver.beckn;

/**
 * The error codes of the {@code Nack}s this service returns for {@code sygnal/signal/emit}
 * (docs/sygnal-integration.md §3.4). Sygnal asks for codes from the canonical Beckn v2 list; these are
 * the ones cce-beckn-service uses too.
 */
public final class ErrorCodes {

    public static final String CTX_MISSING_FIELD = "CTX_MISSING_FIELD";
    public static final String CTX_INVALID_FIELD = "CTX_INVALID_FIELD";
    public static final String CTX_ACTION_MISMATCH = "CTX_ACTION_MISMATCH";
    public static final String SCH_INVALID_JSON = "SCH_INVALID_JSON";
    public static final String SCH_REQUIRED_FIELD_MISSING = "SCH_REQUIRED_FIELD_MISSING";
    public static final String SCH_TYPE_NOT_SUPPORTED = "SCH_TYPE_NOT_SUPPORTED";
    /** Something failed here. Answered with a 5xx: CCE may send the signal again. */
    public static final String NET_INTERNAL_ERROR = "NET_INTERNAL_ERROR";

    private ErrorCodes() {
    }
}
