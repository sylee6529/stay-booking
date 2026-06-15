package com.example.staybooking.adapter.in.web;

import com.example.staybooking.application.StockSyncService;
import com.example.staybooking.domain.booking.BookingRepository;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.payment.PaymentMethod;
import com.example.staybooking.domain.payment.PaymentRepository;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.application.port.out.stock.StockGatePort;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookingControllerTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionProductRepository products;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private BookingRequestRepository bookingRequests;

    @Autowired
    private StockSyncService stockSyncService;

    @Autowired
    private StockGatePort stockGate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void flushRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void 예약생성_성공시_예약_결제_재고상태를_확정한다() throws Exception {
        long productId = newProduct(2);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "book-ok-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(bookings.findAll()).anyMatch(b -> b.getProductId().equals(productId)
                && b.getUserId().equals(userId)
                && b.getStatus().equals("CONFIRMED"));
        assertThat(payments.findAll()).anyMatch(p -> p.getTotalAmount() == 150000
                && p.getExternalAmount() == 150000);
        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isEqualTo(1);
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
    }

    @Test
    void 같은_멱등키_재요청은_확정된_예약을_재생한다() throws Exception {
        long productId = newProduct(2);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();
        String key = "book-dup-" + userId;

        String firstBody = mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(firstBody));

        assertThat(bookingRequests.findByUserIdAndIdempotencyKey(userId, key)).isPresent();
        assertThat(bookings.findAll().stream()
                .filter(b -> b.getProductId().equals(productId) && b.getUserId().equals(userId))
                .count()).isEqualTo(1);
    }

    @Test
    void 같은_멱등키_반복은_RateLimit_카운트를_증가시키지_않고_멱등_재생한다() throws Exception {
        long productId = newProduct(2);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();
        String key = "rate-same-key-" + userId;

        String firstBody = mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/bookings")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cardRequest(productId, userId)))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(firstBody));
        }

        assertThat(bookings.findAll().stream()
                .filter(b -> b.getProductId().equals(productId) && b.getUserId().equals(userId))
                .count()).isEqualTo(1);
    }

    @Test
    void 같은_사용자가_서로_다른_멱등키로_짧은_시간에_반복하면_429를_반환한다() throws Exception {
        long productId = newProduct(10);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/bookings")
                            .header("Idempotency-Key", "rate-new-key-" + userId + "-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cardRequest(productId, userId)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "rate-new-key-" + userId + "-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank());
    }

    @Test
    void 같은_멱등키로_다른_payload를_보내면_409를_반환한다() throws Exception {
        long productId = newProduct(2);
        long otherProductId = newProduct(2);
        stockSyncService.sync(productId);
        stockSyncService.sync(otherProductId);
        long userId = System.nanoTime();
        String key = "book-hash-" + userId;

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(otherProductId, userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"));
    }

    @Test
    void 매진이면_DB_요청기록_없이_409를_반환한다() throws Exception {
        long productId = newProduct(0);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "book-soldout-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));

        assertThat(bookingRequests.findByUserIdAndIdempotencyKey(userId, "book-soldout-" + userId)).isEmpty();
        assertThat(bookings.findAll().stream().noneMatch(b -> b.getProductId().equals(productId))).isTrue();
    }

    @Test
    void Redis가_DB보다_부풀려져도_DB_reserve_단계에서_결제전_거절한다() throws Exception {
        long productId = newProduct(0);
        long userId = System.nanoTime();
        stockGate.overwriteStock(productId, 1);

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "drift-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));

        assertThat(bookingRequests.findByUserIdAndIdempotencyKey(userId, "drift-" + userId))
                .get()
                .extracting(r -> r.getStatus().name())
                .isEqualTo("REJECTED");
        assertThat(bookings.findAll().stream().noneMatch(b -> b.getProductId().equals(productId))).isTrue();
        assertThat(payments.findAll().stream()
                .noneMatch(p -> bookingRequests.findById(p.getBookingRequestId())
                        .map(r -> r.getProductId().equals(productId))
                        .orElse(false))).isTrue();
    }

    @Test
    void 멱등키_헤더가_없으면_400_INVALID_REQUEST를_반환한다() throws Exception {
        long productId = newProduct(1);
        long userId = System.nanoTime();

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cardRequest(productId, userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 카드결제에_카드번호가_없으면_400_INVALID_REQUEST를_반환한다() throws Exception {
        long productId = newProduct(1);
        long userId = System.nanoTime();

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "missing-card-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": %d,
                                  "userId": %d,
                                  "paymentMethods": ["CREDIT_CARD"],
                                  "pointAmount": 0
                                }
                                """.formatted(productId, userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 카드와_YPay를_함께_보내면_400_INVALID_PAYMENT_COMBINATION을_반환한다() throws Exception {
        long productId = newProduct(1);
        long userId = System.nanoTime();

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "invalid-combo-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": %d,
                                  "userId": %d,
                                  "paymentMethods": ["CREDIT_CARD", "Y_PAY"],
                                  "pointAmount": 0,
                                  "cardNumber": "4111-1111-1111-1234",
                                  "ypayToken": "valid-token"
                                }
                                """.formatted(productId, userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_COMBINATION"));
    }

    @Test
    void internal_stock_sync는_DB_available_기준으로_Redis를_덮어쓴다() throws Exception {
        long productId = newProduct(3);
        stockGate.overwriteStock(productId, 99);

        mockMvc.perform(post("/internal/products/{productId}/stock-sync", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SYNCED"));

        assertThat(stockGate.currentStock(productId)).isEqualTo(3);
    }

    @Test
    void 재고10에_동시예약_1000건이면_정확히_10건만_확정된다() throws Exception {
        int stock = 10;
        int threads = 1000;
        long productId = newProduct(stock);
        stockSyncService.sync(productId);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            long userId = System.nanoTime() + i;
            String key = "book-t1-" + userId;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/bookings")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(cardRequest(productId, userId)))
                            .andReturn().getResponse().getStatus();
                    if (status == 200) {
                        ok.incrementAndGet();
                    } else if (status == 409) {
                        soldOut.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).isEqualTo(stock);
        assertThat(soldOut.get()).isEqualTo(threads - stock);
        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isZero();
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isEqualTo(stock);
        assertThat(stockGate.currentStock(productId)).isZero();
        assertThat(bookings.findAll().stream().filter(b -> b.getProductId().equals(productId)).count())
                .isEqualTo(stock);
        assertThat(payments.findAll().stream()
                .filter(p -> bookings.findByBookingRequestId(p.getBookingRequestId())
                        .map(b -> b.getProductId().equals(productId))
                        .orElse(false))
                .count()).isEqualTo(stock);
    }

    @Test
    void 카드거절이면_포인트와_재고를_보상하고_FAILED로_남긴다() throws Exception {
        long productId = newProduct(1);
        stockSyncService.sync(productId);
        long userId = System.nanoTime();
        userPoints().save(new com.example.staybooking.domain.point.UserPoints(userId, 30000));

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "book-fail-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": %d,
                                  "userId": %d,
                                  "paymentMethods": ["CREDIT_CARD", "Y_POINT"],
                                  "pointAmount": 30000,
                                  "cardNumber": "4111-1111-1111-0000"
                                }
                                """.formatted(productId, userId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        PromotionProduct after = products.findById(productId).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(1);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(after.getSoldQuantity()).isZero();
        assertThat(stockGate.currentStock(productId)).isEqualTo(1);
        assertThat(userPoints().findByUserId(userId).orElseThrow().getBalance()).isEqualTo(30000);
        assertThat(bookings.findAll().stream().noneMatch(b -> b.getProductId().equals(productId))).isTrue();
        assertThat(bookingRequests.findByUserIdAndIdempotencyKey(userId, "book-fail-" + userId))
                .get()
                .extracting(r -> r.getStatus().name())
                .isEqualTo("FAILED");
    }

    private long newProduct(int totalQuantity) {
        long id = System.nanoTime();
        products.save(new PromotionProduct(
                id, "테스트 숙소", 150000, totalQuantity,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                LocalDateTime.now().minusDays(1)));
        return id;
    }

    private String cardRequest(long productId, long userId) {
        return """
                {
                  "productId": %d,
                  "userId": %d,
                  "paymentMethods": ["%s"],
                  "pointAmount": 0,
                  "cardNumber": "4111-1111-1111-1234"
                }
                """.formatted(productId, userId, PaymentMethod.CREDIT_CARD.name());
    }

    @Autowired
    private com.example.staybooking.domain.point.UserPointsRepository userPoints;

    private com.example.staybooking.domain.point.UserPointsRepository userPoints() {
        return userPoints;
    }
}
