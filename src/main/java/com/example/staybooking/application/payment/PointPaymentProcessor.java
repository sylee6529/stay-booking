package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.payment.PaymentMethod;
import com.example.staybooking.domain.payment.PointHistory;
import com.example.staybooking.domain.payment.PointHistoryRepository;
import com.example.staybooking.domain.payment.PointHistoryType;
import com.example.staybooking.domain.point.UserPointsRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 포인트 차감/환불은 예약 트랜잭션과 분리된 짧은 트랜잭션으로 수행한다.
 */
@Component
public class PointPaymentProcessor implements PaymentProcessor {

    private final UserPointsRepository userPoints;
    private final PointHistoryRepository pointHistory;

    public PointPaymentProcessor(UserPointsRepository userPoints, PointHistoryRepository pointHistory) {
        this.userPoints = userPoints;
        this.pointHistory = pointHistory;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.Y_POINT;
    }

    @Override
    @Transactional
    public ProcessorResult approve(PaymentContext context, long amount) {
        int updated = userPoints.deduct(context.userId(), amount);
        if (updated == 0) {
            return ProcessorResult.declined(ErrorCode.INSUFFICIENT_POINT, "포인트 잔액 부족");
        }
        pointHistory.save(new PointHistory(
                context.bookingRequestId(), PointHistoryType.USE, amount, LocalDateTime.now()));
        return ProcessorResult.approved("PT-" + UUID.randomUUID());
    }

    @Override
    @Transactional
    public void cancel(PaymentContext context, String transactionId, long amount) {
        if (pointHistory.findByBookingRequestIdAndType(
                context.bookingRequestId(), PointHistoryType.REFUND).isPresent()) {
            return;
        }
        try {
            pointHistory.save(new PointHistory(
                    context.bookingRequestId(), PointHistoryType.REFUND, amount, LocalDateTime.now()));
        } catch (DataIntegrityViolationException e) {
            return;
        }
        userPoints.refund(context.userId(), amount);
    }
}
