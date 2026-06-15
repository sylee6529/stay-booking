package com.example.staybooking.domain.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPointsRepository extends JpaRepository<UserPoints, Long> {

    Optional<UserPoints> findByUserId(Long userId);

    /**
     * 포인트 차감. {@code WHERE balance >= :amount} 조건이 음수 잔액(초과 차감)을 물리적으로 막는다
     * (불변식 #8, docs/06).
     *
     * @return 1 = 차감 성공, 0 = 잔액 부족(INSUFFICIENT_POINT)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserPoints u
               SET u.balance = u.balance - :amount
             WHERE u.userId = :userId
               AND u.balance >= :amount
            """)
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 포인트 환불(보상). 잔액 상한 검증이 없으므로 항상 1을 기대하지만,
     * 행이 없으면 0이다(이상 상태).
     *
     * @return 1 = 환불 반영, 0 = 대상 사용자 없음
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserPoints u
               SET u.balance = u.balance + :amount
             WHERE u.userId = :userId
            """)
    int refund(@Param("userId") Long userId, @Param("amount") long amount);
}
