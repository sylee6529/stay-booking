package com.example.staybooking.adapter.in.web;

import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.stock-sync-on-startup=false",
        "app.recovery.enabled=false",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1"
})
@AutoConfigureMockMvc
class BookingRedisFailClosedTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("staybooking")
            .withUsername("app")
            .withPassword("app1234");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionProductRepository products;

    @Test
    void Redis_연결불가면_예약을_503으로_FailClosed한다() throws Exception {
        long productId = System.nanoTime();
        long userId = System.nanoTime();
        products.save(new PromotionProduct(
                productId, "Redis 장애 숙소", 150000, 10,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2),
                LocalDateTime.now().minusDays(1)));

        mockMvc.perform(post("/api/bookings")
                        .header("Idempotency-Key", "redis-down-" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": %d,
                                  "userId": %d,
                                  "paymentMethods": ["CREDIT_CARD"],
                                  "pointAmount": 0,
                                  "cardNumber": "4111-1111-1111-1234"
                                }
                                """.formatted(productId, userId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STOCK_GATE_UNAVAILABLE"));
    }
}
