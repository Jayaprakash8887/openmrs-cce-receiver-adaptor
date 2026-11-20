package org.openphc.cce.receiver.beckn;

import org.springframework.http.HttpStatus;

/** A request that is answered with a {@code Nack}: the HTTP status, the error code and the text. */
public class BecknException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BecknException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** The sender's error (400): the message must change before it is sent again. */
    public static BecknException badRequest(String code, String message) {
        return new BecknException(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
