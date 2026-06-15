package com.example.staybooking.domain;

import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 조건부 UPDATE의 원자성 검증 (불변식 #1, #8, docs/04).
 *
 * <p>각 스레드가 독립 트랜잭션으로 reserveOne을 호출해도 성공 건수가 재고 수량을 넘지 않음을 증명한다.
 * 이것이 Step 5의 통합 T1(1000 동시 요청)을 떠받치는 저수준 보장이다.
 */
class PromotionProductRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private PromotionProductRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    private long uniqueId() {
        return System.nanoTime();
    }

    private Long saveProduct(int totalQuantity) {
        long id = uniqueId();
        PromotionProduct product = new PromotionProduct(
                id, "테스트 상품", 150000, totalQuantity,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                LocalDateTime.of(2026, 1, 1, 0, 0));
        repository.save(product);
        return id;
    }

    @Test
    void 재고보다_많은_동시_예약요청에도_성공건수는_재고를_넘지_않는다() throws Exception {
        int stock = 10;
        int threads = 100;
        Long productId = saveProduct(stock);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        // 풀 크기 == 태스크 수: 모든 태스크가 start 배리어에 동시에 도달해야 동시성이 의미를 가진다.
        // 풀이 더 작으면 먼저 실행된 태스크가 배리어에서 멈춰 나머지가 영원히 큐에 갇힌다(데드락).
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Integer updated = tx.execute(s -> repository.reserveOne(productId));
                    if (updated != null && updated == 1) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(success.get()).isEqualTo(stock);
        PromotionProduct after = repository.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isZero();
        assertThat(after.getReservedQuantity()).isEqualTo(stock);
        assertThat(after.getSoldQuantity()).isZero();
    }

    private int reserveInTx(TransactionTemplate tx, Long productId) {
        Integer updated = tx.execute(s -> repository.reserveOne(productId));
        return updated == null ? 0 : updated;
    }

    @Test
    void 매진_상태에서는_reserveOne이_0을_반환한다() {
        Long productId = saveProduct(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThat(reserveInTx(tx, productId)).isEqualTo(1);
        assertThat(reserveInTx(tx, productId)).isZero();
    }

    @Test
    void confirmOne은_예약수량을_판매수량으로_옮긴다() {
        Long productId = saveProduct(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        reserveInTx(tx, productId);

        Integer confirmed = tx.execute(s -> repository.confirmOne(productId));
        assertThat(confirmed).isEqualTo(1);

        PromotionProduct after = repository.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isZero();
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isEqualTo(1);
    }

    @Test
    void releaseOne은_예약수량을_가용수량으로_되돌린다() {
        Long productId = saveProduct(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        reserveInTx(tx, productId);

        Integer released = tx.execute(s -> repository.releaseOne(productId));
        assertThat(released).isEqualTo(1);

        PromotionProduct after = repository.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isZero();
    }
}
