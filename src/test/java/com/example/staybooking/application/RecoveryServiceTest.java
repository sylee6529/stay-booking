package com.example.staybooking.application;

import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.domain.booking.PgStatus;
import com.example.staybooking.domain.payment.PaymentRepository;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.stock.StockGatePort;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryServiceTest extends IntegrationTestSupport {

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private BookingCompensationService compensationService;

    @Autowired
    private BookingRequestRepository bookingRequests;

    @Autowired
    private PromotionProductRepository products;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private StockSyncService stockSyncService;

    @Autowired
    private StockGatePort stockGate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void flushRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void STOCK_RESERVED_만료는_재고를_복구하고_REJECTED로_종결한다() {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        stockGate.admit(productId, 1L, "expired-key");

        BookingRequest request = newRequest(1L, productId, "expired-key");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            BookingRequest saved = bookingRequests.saveAndFlush(request);
            products.reserveOne(productId);
            bookingRequests.markStockReserved(saved.getId(), BookingStatus.STOCK_RESERVED,
                    LocalDateTime.now().minusSeconds(1), LocalDateTime.now().minusMinutes(5));
        });

        recoveryService.recoverOnce();

        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isZero();
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
        assertThat(bookingRequests.findByUserIdAndIdempotencyKey(1L, "expired-key").orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REJECTED);
    }

    @Test
    void APPROVED_stuck은_확정트랜잭션을_재시도해_CONFIRMED로_수렴한다() {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        stockGate.admit(productId, 2L, "approved-key");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        BookingRequest saved = tx.execute(s -> {
            BookingRequest request = bookingRequests.saveAndFlush(newRequest(2L, productId, "approved-key"));
            products.reserveOne(productId);
            bookingRequests.markApproved(request.getId(), BookingStatus.APPROVED, PgStatus.APPROVED,
                    "PG-RECOVERY", LocalDateTime.now().minusMinutes(2));
            return bookingRequests.findById(request.getId()).orElseThrow();
        });

        recoveryService.recoverOnce();

        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isZero();
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isEqualTo(1);
        assertThat(bookingRequests.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(payments.findByBookingRequestId(saved.getId())).isPresent();
    }

    @Test
    void APPROVING_lease_만료는_보상경로로_FAILED에_수렴한다() {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        stockGate.admit(productId, 3L, "approving-key");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        BookingRequest saved = tx.execute(s -> {
            BookingRequest request = bookingRequests.saveAndFlush(newRequest(3L, productId, "approving-key"));
            products.reserveOne(productId);
            bookingRequests.markApproving(request.getId(), BookingStatus.APPROVING, PgStatus.APPROVING,
                    LocalDateTime.now().minusSeconds(1), LocalDateTime.now().minusMinutes(5));
            return bookingRequests.findById(request.getId()).orElseThrow();
        });

        recoveryService.recoverOnce();

        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isZero();
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
        assertThat(bookingRequests.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.FAILED);
    }

    @Test
    void 보상_동시진입은_CAS로_재고를_한번만_복구한다() throws Exception {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        stockGate.admit(productId, 4L, "comp-key");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        BookingRequest saved = tx.execute(s -> {
            BookingRequest request = bookingRequests.saveAndFlush(newRequest(4L, productId, "comp-key"));
            products.reserveOne(productId);
            bookingRequests.markPaymentFailed(request.getId(), BookingStatus.PAYMENT_FAILED, PgStatus.DECLINED,
                    "DECLINED", LocalDateTime.now());
            return bookingRequests.findById(request.getId()).orElseThrow();
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    compensationService.compensate(saved.getId(), com.example.staybooking.application.error.ErrorCode.PAYMENT_DECLINED,
                            saved.getIdempotencyKey());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isZero();
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
        assertThat(bookingRequests.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.FAILED);
    }

    private long newProduct(int totalQuantity) {
        long id = System.nanoTime();
        products.save(new PromotionProduct(
                id, "복구 테스트 숙소", 150000, totalQuantity,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                LocalDateTime.now().minusDays(1)));
        return id;
    }

    private BookingRequest newRequest(long userId, long productId, String key) {
        return BookingRequest.received(key, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                userId, productId, "CREDIT_CARD", 150000, 0, LocalDateTime.now().minusMinutes(5));
    }
}
