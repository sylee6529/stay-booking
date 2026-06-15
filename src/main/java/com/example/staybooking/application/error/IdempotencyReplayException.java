package com.example.staybooking.application.error;

/**
 * Stored terminal idempotency response. The adapter returns the saved response
 * body as-is so repeated requests keep the original outcome.
 */
public class IdempotencyReplayException extends RuntimeException {

    private final int httpStatus;
    private final String responseBody;

    public IdempotencyReplayException(int httpStatus, String responseBody) {
        super("idempotency replay");
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
