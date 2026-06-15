package com.example.staybooking.domain;

import com.example.staybooking.domain.booking.BookingRequest;
import com.example.staybooking.domain.booking.BookingRequestRepository;
import com.example.staybooking.domain.booking.BookingStatus;
import com.example.staybooking.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멱등성 최후 방어선(UNIQUE)과 상태 전이 CAS 가드 검증 (불변식 #2, #7, #8, docs/05·06·07).
 */
class BookingRequestRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private BookingRequestRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    private BookingRequest received(Long userId, String key, String hash) {
        return BookingRequest.received(key, hash, userId, 1L, "CREDIT_CARD",
                150000, 0, LocalDateTime.now());
    }

    @Test
    void 같은_user_와_idempotencyKey_중복_INSERT는_UNIQUE제약으로_거절된다() {
        long userId = System.nanoTime();
        String key = "KEY-" + userId;
        repository.saveAndFlush(received(userId, key, "hash-a"));

        assertThatThrownBy(() -> repository.saveAndFlush(received(userId, key, "hash-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compareAndSetStatus는_현재상태가_일치할때만_전이하고_권리는_한_경로만_얻는다() {
        long userId = System.nanoTime();
        BookingRequest saved = repository.saveAndFlush(received(userId, "KEY-" + userId, "hash"));
        Long id = saved.getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        LocalDateTime now = LocalDateTime.now();

        // 첫 전이: RECEIVED -> STOCK_RESERVED 성공
        Integer first = tx.execute(s ->
                repository.compareAndSetStatus(id, BookingStatus.RECEIVED, BookingStatus.STOCK_RESERVED, now));
        assertThat(first).isEqualTo(1);

        // 같은 from(RECEIVED)으로 다시 시도하면 이미 옮겨졌으므로 0 (보상 중복 진입 방지와 동일 원리)
        Integer second = tx.execute(s ->
                repository.compareAndSetStatus(id, BookingStatus.RECEIVED, BookingStatus.STOCK_RESERVED, now));
        assertThat(second).isZero();

        BookingRequest after = repository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BookingStatus.STOCK_RESERVED);
    }
}
