package com.example.staybooking.application;

import com.example.staybooking.application.booking.BookingCreateResult;
import com.example.staybooking.application.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class StoredResponseFactory {

    public String confirmed(BookingCreateResult response) {
        return """
                {"bookingId":%d,"status":"%s"}
                """.formatted(response.bookingId(), response.status()).trim();
    }

    public String error(ErrorCode code, String traceId) {
        return error(code.name(), code.getMessage(), traceId);
    }

    public String error(ErrorCode code, String message, String traceId) {
        return error(code.name(), message == null ? code.getMessage() : message, traceId);
    }

    public String error(String code, String message, String traceId) {
        return """
                {"code":"%s","message":"%s","traceId":"%s"}
                """.formatted(code, message, traceId).trim();
    }
}
