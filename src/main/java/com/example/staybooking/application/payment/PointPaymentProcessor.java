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
 * 자사 포인트 결제 (docs/06). 보상이 쉬운 자사 DB라 가장 먼저 차감하고 마지막에 보상한다.
 *
 * <p>차감/환불 모두 조건부 UPDATE + {@code point_history} UNIQUE(booking_request_id, type)로
 * effectively-once를 보장한다 (불변식 #2, #8). 전체 Booking 트랜잭션과 분리된 짧은 독립 tx로 수행한다.
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
        // 조건부 UPDATE: balance >= amount 일 때만 차감 (불변식 #8). 0 rows = 잔액 부족.
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
        // 멱등 환불: REFUND 이력이 이미 있으면 잔액을 다시 늘리지 않는다.
        if (pointHistory.findByBookingRequestIdAndType(
                context.bookingRequestId(), PointHistoryType.REFUND).isPresent()) {
            return;
        }
        try {
            pointHistory.save(new PointHistory(
                    context.bookingRequestId(), PointHistoryType.REFUND, amount, LocalDateTime.now()));
        } catch (DataIntegrityViolationException e) {
            // 동시 보상 경로가 먼저 환불함 → UNIQUE 충돌. 잔액 증가 스킵.
            return;
        }
        userPoints.refund(context.userId(), amount);
    }
}
