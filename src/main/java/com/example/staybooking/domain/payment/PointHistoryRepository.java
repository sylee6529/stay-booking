package com.example.staybooking.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    Optional<PointHistory> findByBookingRequestIdAndType(Long bookingRequestId, PointHistoryType type);
}
