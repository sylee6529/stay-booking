package com.example.staybooking.domain.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRequestRepository extends JpaRepository<BookingRequest, Long> {

    Optional<BookingRequest> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookingRequest b WHERE b.id = :id")
    Optional<BookingRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT b
              FROM BookingRequest b
             WHERE b.status = :status
               AND b.reservationExpiresAt < :now
            """)
    List<BookingRequest> findExpiredReservations(@Param("status") BookingStatus status,
                                                 @Param("now") LocalDateTime now);

    @Query("""
            SELECT b
              FROM BookingRequest b
             WHERE b.status = :status
               AND b.pgTxId IS NOT NULL
               AND b.updatedAt < :cutoff
            """)
    List<BookingRequest> findApprovedStuck(@Param("status") BookingStatus status,
                                           @Param("cutoff") LocalDateTime cutoff);

    @Query("""
            SELECT b
              FROM BookingRequest b
             WHERE b.status = :status
               AND b.leaseExpiresAt < :now
            """)
    List<BookingRequest> findExpiredApproving(@Param("status") BookingStatus status,
                                              @Param("now") LocalDateTime now);

    List<BookingRequest> findByStatus(BookingStatus status);

    /**
     * 보상/복구가 동시에 시도돼도 한 경로만 상태 전이 권리를 얻는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :to,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.status = :from
            """)
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("from") BookingStatus from,
                            @Param("to") BookingStatus to,
                            @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.reservationExpiresAt = :reservationExpiresAt,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markStockReserved(@Param("id") Long id,
                          @Param("status") BookingStatus status,
                          @Param("reservationExpiresAt") LocalDateTime reservationExpiresAt,
                          @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.leaseExpiresAt = :leaseExpiresAt,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markApproving(@Param("id") Long id,
                      @Param("status") BookingStatus status,
                      @Param("pgStatus") PgStatus pgStatus,
                      @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                      @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.pgTxId = :pgTxId,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.status = :expected
            """)
    int markApprovedFromStatus(@Param("id") Long id,
                               @Param("expected") BookingStatus expected,
                               @Param("status") BookingStatus status,
                               @Param("pgStatus") PgStatus pgStatus,
                               @Param("pgTxId") String pgTxId,
                               @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.pgTxId = :pgTxId,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markApproved(@Param("id") Long id,
                     @Param("status") BookingStatus status,
                     @Param("pgStatus") PgStatus pgStatus,
                     @Param("pgTxId") String pgTxId,
                     @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.failureReason = :failureReason,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markPaymentFailed(@Param("id") Long id,
                          @Param("status") BookingStatus status,
                          @Param("pgStatus") PgStatus pgStatus,
                          @Param("failureReason") String failureReason,
                          @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.failureReason = :failureReason,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.status = :expected
            """)
    int markPaymentFailedFromStatus(@Param("id") Long id,
                                    @Param("expected") BookingStatus expected,
                                    @Param("status") BookingStatus status,
                                    @Param("pgStatus") PgStatus pgStatus,
                                    @Param("failureReason") String failureReason,
                                    @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.pointsRefunded = true,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markPointsRefunded(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.stockRestoreStatus = :stockRestoreStatus,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int markStockRestored(@Param("id") Long id,
                          @Param("stockRestoreStatus") StockRestoreStatus stockRestoreStatus,
                          @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.responseCode = :responseCode,
                   b.responseBody = :responseBody,
                   b.completedAt = :now,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int complete(@Param("id") Long id,
                 @Param("status") BookingStatus status,
                 @Param("responseCode") int responseCode,
                 @Param("responseBody") String responseBody,
                 @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE BookingRequest b
               SET b.status = :status,
                   b.pgStatus = :pgStatus,
                   b.failureReason = :failureReason,
                   b.responseCode = :responseCode,
                   b.responseBody = :responseBody,
                   b.completedAt = :now,
                   b.updatedAt = :now
             WHERE b.id = :id
            """)
    int failTerminal(@Param("id") Long id,
                     @Param("status") BookingStatus status,
                     @Param("pgStatus") PgStatus pgStatus,
                     @Param("failureReason") String failureReason,
                     @Param("responseCode") int responseCode,
                     @Param("responseBody") String responseBody,
                     @Param("now") LocalDateTime now);
}
