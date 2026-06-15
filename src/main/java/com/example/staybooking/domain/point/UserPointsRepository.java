package com.example.staybooking.domain.point;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPointsRepository extends JpaRepository<UserPoints, Long> {

    Optional<UserPoints> findByUserId(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserPoints u
               SET u.balance = u.balance - :amount
             WHERE u.userId = :userId
               AND u.balance >= :amount
            """)
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserPoints u
               SET u.balance = u.balance + :amount
             WHERE u.userId = :userId
            """)
    int refund(@Param("userId") Long userId, @Param("amount") long amount);
}
