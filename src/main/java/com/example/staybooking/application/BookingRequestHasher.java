package com.example.staybooking.application;

import com.example.staybooking.application.booking.BookingCreateCommand;
import com.example.staybooking.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class BookingRequestHasher {

    public String hash(BookingCreateCommand request, long amount) {
        String methods = request.paymentMethods().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(PaymentMethod::name)
                .collect(Collectors.joining(","));
        String canonical = request.userId() + "|" + request.productId() + "|" + methods + "|"
                + amount + "|" + request.pointAmount() + "|"
                + nullToEmpty(request.cardNumber()) + "|" + nullToEmpty(request.ypayToken());
        return sha256(canonical);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
