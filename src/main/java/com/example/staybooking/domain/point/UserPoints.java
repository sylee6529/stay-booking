package com.example.staybooking.domain.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 자사 포인트 잔액 (Y Point). 차감/환불은 setter가 아니라
 * {@link UserPointsRepository}의 조건부 UPDATE로만 한다 (불변식 #8, docs/06).
 */
@Entity
@Table(name = "user_points")
public class UserPoints {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private long balance;

    protected UserPoints() {
    }

    public UserPoints(Long userId, long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }
}
