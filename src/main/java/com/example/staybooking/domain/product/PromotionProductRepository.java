package com.example.staybooking.domain.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 갱신은 전부 조건부 벌크 UPDATE로만 수행한다 (불변식 #8, docs/04).
 *
 * <p>각 메서드는 affected rows 수를 반환한다. 0이면 조건 미충족(매진/예약 없음)이며,
 * 호출자는 이를 재고 소진/drift 신호로 해석한다. 절대 read-modify-write 하지 않는다.
 *
 * <p>{@code clearAutomatically=true}: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로,
 * 같은 트랜잭션에서 이후 엔티티를 다시 조회할 때 stale 캐시를 보지 않도록 비운다.
 */
public interface PromotionProductRepository extends JpaRepository<PromotionProduct, Long> {

    /**
     * Redis admission 통과 후 DB 재고 예약: available -> reserved.
     * {@code WHERE available_quantity > 0} 가 oversell 최후 방어선이다.
     *
     * @return 1 = 예약 성공, 0 = DB 기준 매진(SOLD_OUT_DB)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PromotionProduct p
               SET p.availableQuantity = p.availableQuantity - 1,
                   p.reservedQuantity  = p.reservedQuantity + 1
             WHERE p.id = :id
               AND p.availableQuantity > 0
            """)
    int reserveOne(@Param("id") Long id);

    /**
     * 결제 성공 후 확정: reserved -> sold.
     *
     * @return 1 = 확정 성공, 0 = 예약 수량 없음(이상 상태)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PromotionProduct p
               SET p.reservedQuantity = p.reservedQuantity - 1,
                   p.soldQuantity     = p.soldQuantity + 1
             WHERE p.id = :id
               AND p.reservedQuantity > 0
            """)
    int confirmOne(@Param("id") Long id);

    /**
     * 결제 실패/예약 만료 시 복구: reserved -> available.
     *
     * @return 1 = 복구 성공, 0 = 되돌릴 예약 수량 없음
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PromotionProduct p
               SET p.reservedQuantity  = p.reservedQuantity - 1,
                   p.availableQuantity = p.availableQuantity + 1
             WHERE p.id = :id
               AND p.reservedQuantity > 0
            """)
    int releaseOne(@Param("id") Long id);
}
