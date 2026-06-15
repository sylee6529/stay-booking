package com.example.staybooking.domain.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 갱신은 조건부 벌크 UPDATE로만 수행한다. affected rows가 0이면 조건 미충족이다.
 *
 * <p>{@code clearAutomatically=true}: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로,
 * 같은 트랜잭션에서 이후 엔티티를 다시 조회할 때 stale 캐시를 보지 않도록 비운다.
 */
public interface PromotionProductRepository extends JpaRepository<PromotionProduct, Long> {

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PromotionProduct p
               SET p.availableQuantity = p.availableQuantity - 1,
                   p.reservedQuantity  = p.reservedQuantity + 1
             WHERE p.id = :id
               AND p.availableQuantity > 0
            """)
    int reserveOne(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE PromotionProduct p
               SET p.reservedQuantity = p.reservedQuantity - 1,
                   p.soldQuantity     = p.soldQuantity + 1
             WHERE p.id = :id
               AND p.reservedQuantity > 0
            """)
    int confirmOne(@Param("id") Long id);

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
