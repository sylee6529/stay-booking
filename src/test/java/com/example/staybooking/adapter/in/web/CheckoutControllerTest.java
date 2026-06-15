package com.example.staybooking.adapter.in.web;

import com.example.staybooking.domain.point.UserPoints;
import com.example.staybooking.domain.point.UserPointsRepository;
import com.example.staybooking.domain.product.PromotionProduct;
import com.example.staybooking.domain.product.PromotionProductRepository;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CheckoutControllerTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PromotionProductRepository products;

    @Autowired
    private UserPointsRepository userPoints;

    private long newProduct(LocalDateTime openAt) {
        long id = System.nanoTime();
        products.save(new PromotionProduct(
                id, "제주 오션뷰 스테이", 150000, 10,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), openAt));
        return id;
    }

    @Test
    void 오픈된_상품과_포인트잔액을_반환한다() throws Exception {
        long productId = newProduct(LocalDateTime.now().minusDays(1));
        long userId = System.nanoTime();
        userPoints.save(new UserPoints(userId, 50000));

        mockMvc.perform(get("/api/checkout").param("productId", String.valueOf(productId))
                        .param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.name").value("제주 오션뷰 스테이"))
                .andExpect(jsonPath("$.price").value(150000))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.pointBalance").value(50000));
    }

    @Test
    void 포인트행이_없는_사용자는_잔액0으로_응답한다() throws Exception {
        long productId = newProduct(LocalDateTime.now().minusDays(1));
        long userId = System.nanoTime(); // user_points 행 없음

        mockMvc.perform(get("/api/checkout").param("productId", String.valueOf(productId))
                        .param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointBalance").value(0));
    }

    @Test
    void 오픈전_상품은_open_false로_응답한다() throws Exception {
        long productId = newProduct(LocalDateTime.now().plusDays(1));
        long userId = System.nanoTime();

        mockMvc.perform(get("/api/checkout").param("productId", String.valueOf(productId))
                        .param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void 없는_상품은_404_PRODUCT_NOT_FOUND_봉투를_반환한다() throws Exception {
        mockMvc.perform(get("/api/checkout").param("productId", "-1")
                        .param("userId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
