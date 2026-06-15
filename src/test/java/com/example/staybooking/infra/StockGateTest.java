package com.example.staybooking.infra;

import com.example.staybooking.application.StockSyncService;
import com.example.staybooking.application.stock.AdmissionResult;
import com.example.staybooking.application.stock.StockGateUnavailableException;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis admission 게이트의 원자성·Fail-Closed·단방향 sync 검증 (불변식 #1, #5, #6, docs/04).
 */
class StockGateTest extends IntegrationTestSupport {

    @Autowired
    private StockGate stockGate;

    @Autowired
    private StockSyncService stockSyncService;

    @Autowired
    private PromotionProductRepository products;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * admission 키(TTL 5s)는 테스트 종료 후에도 잠시 살아남아 다른 테스트의 (userId, key) 재사용과
     * 충돌한다. 공유 컨테이너이므로 각 테스트 전에 Redis를 비워 격리한다.
     */
    @BeforeEach
    void flushRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private long newProduct(int totalQuantity) {
        long id = System.nanoTime();
        products.save(new PromotionProduct(
                id, "테스트 상품", 150000, totalQuantity,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                LocalDateTime.of(2026, 1, 1, 0, 0)));
        return id;
    }

    @Test
    void admit는_재고를_원자적으로_선점하고_남은수량을_돌려준다() {
        long productId = newProduct(3);
        stockSyncService.sync(productId);

        AdmissionResult first = stockGate.admit(productId, 1L, "key-1");

        assertThat(first.isReserved()).isTrue();
        assertThat(first.remaining()).isEqualTo(2);
        assertThat(stockGate.currentStock(productId)).isEqualTo(2);
    }

    @Test
    void 같은_user_key_admission은_중복으로_판정되고_재고를_두번_차감하지_않는다() {
        long productId = newProduct(3);
        stockSyncService.sync(productId);

        AdmissionResult first = stockGate.admit(productId, 1L, "dup-key");
        AdmissionResult second = stockGate.admit(productId, 1L, "dup-key");

        assertThat(first.isReserved()).isTrue();
        assertThat(second.outcome()).isEqualTo(AdmissionResult.Outcome.DUPLICATE);
        assertThat(stockGate.currentStock(productId)).isEqualTo(2); // 1회만 차감
    }

    @Test
    void 매진이면_SOLD_OUT을_반환한다() {
        long productId = newProduct(1);
        stockSyncService.sync(productId);

        assertThat(stockGate.admit(productId, 1L, "k1").isReserved()).isTrue();
        assertThat(stockGate.admit(productId, 2L, "k2").outcome())
                .isEqualTo(AdmissionResult.Outcome.SOLD_OUT);
    }

    @Test
    void stock키가_없으면_FailClosed로_예외를_던진다() {
        long productId = newProduct(5); // sync 하지 않아 Redis 키 부재

        assertThatThrownBy(() -> stockGate.admit(productId, 1L, "k1"))
                .isInstanceOf(StockGateUnavailableException.class);
    }

    @Test
    void tryRelease는_재고를_1_증가시킨다() {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        stockGate.admit(productId, 1L, "k1"); // 1 -> 0

        assertThat(stockGate.tryRelease(productId)).isTrue();
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
    }

    @Test
    void sync는_DB_available로_Redis를_덮어쓰며_여러번_실행해도_안전하다() {
        long productId = newProduct(7);

        stockSyncService.sync(productId);
        stockGate.admit(productId, 1L, "k1"); // Redis 6, DB available 7 (admit은 DB 미반영)

        // 다시 sync 하면 DB 기준(7)으로 덮어쓴다 — 멱등
        stockSyncService.sync(productId);
        assertThat(stockGate.currentStock(productId)).isEqualTo(7);
    }

    @Test
    void 재고10에_동시_admission_100건이면_정확히_10건만_선점된다() throws Exception {
        int stock = 10;
        int threads = 100;
        long productId = newProduct(stock);
        stockSyncService.sync(productId);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            long userId = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    AdmissionResult r = stockGate.admit(productId, userId, "key-" + userId);
                    if (r.isReserved()) {
                        reserved.incrementAndGet();
                    } else if (r.outcome() == AdmissionResult.Outcome.SOLD_OUT) {
                        soldOut.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(reserved.get()).isEqualTo(stock);
        assertThat(soldOut.get()).isEqualTo(threads - stock);
        assertThat(stockGate.currentStock(productId)).isZero();
    }
}
