package org.openphc.cce.receiver.beckn;

import org.springframework.http.HttpStatus;

/** A request that is answered with a {@code Nack}: the HTTP status, the code and the text. */
public class BecknException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BecknException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BecknException badRequest(String message) {
        return new BecknException(HttpStatus.BAD_REQUEST, BecknCodes.BAD_REQUEST, message);
    }

    public static BecknException notSupported(String action) {
        return new BecknException(HttpStatus.BAD_REQUEST, BecknCodes.NOT_SUPPORTED,
                "The action '" + BecknMessageLog.clean(action) + "' is not supported");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
