package com.example.staybooking.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 프로모션 상품 + 재고. 재고는 available/reserved/sold 세 값으로 분리한다 (docs/04, docs/11).
 *
 * <p>재고 수량 변경은 이 엔티티의 setter/dirty-checking으로 하지 않는다.
 * 반드시 {@link PromotionProductRepository}의 조건부 벌크 UPDATE를 통해서만 갱신한다
 * (불변식 #8: JPA read-modify-write 금지).
 */
@Entity
@Table(name = "promotion_products")
public class PromotionProduct {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private long price;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    @Column(name = "checkin_date", nullable = false)
    private LocalDate checkinDate;

    @Column(name = "checkout_date", nullable = false)
    private LocalDate checkoutDate;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    protected PromotionProduct() {
    }

    public PromotionProduct(Long id, String name, long price, int totalQuantity,
                            LocalDate checkinDate, LocalDate checkoutDate, LocalDateTime openAt) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.totalQuantity = totalQuantity;
        this.availableQuantity = totalQuantity;
        this.reservedQuantity = 0;
        this.soldQuantity = 0;
        this.checkinDate = checkinDate;
        this.checkoutDate = checkoutDate;
        this.openAt = openAt;
    }

    public boolean isOpen(LocalDateTime now) {
        return !now.isBefore(openAt);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public int getSoldQuantity() {
        return soldQuantity;
    }

    public LocalDate getCheckinDate() {
        return checkinDate;
    }

    public LocalDate getCheckoutDate() {
        return checkoutDate;
    }

    public LocalDateTime getOpenAt() {
        return openAt;
    }
}
