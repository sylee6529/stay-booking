# 06. 결제 설계

> 상태: **확정**

## 요구

- 지원 조합: Card / YPay / Point / Card+Point / YPay+Point. **Card+YPay 동시 사용 금지.**
- Strategy Pattern. BookingService는 구체 결제 수단 클래스를 모른다.
- 결제는 시뮬레이션이되, 인터페이스는 실제 외부 연동 형태를 유지한다.

## 구조

```
BookingService
    │  (PaymentCommand — 수단 목록, 금액, 포인트 사용액)
    ▼
PaymentOrchestrator                ← 조합 검증 / 금액 분배 / 실행 순서 / 부분 실패 보상
    │ method() 로 선택
    ▼
PaymentProcessor (interface)       ← 전략
 ├── PointPaymentProcessor         → user_points 조건부 UPDATE (자사 DB)
 ├── CreditCardPaymentProcessor ──┐
 └── YPayPaymentProcessor ────────┴─▶ ExternalPaymentGateway (infra, 시뮬레이터)
```

- 개별 Processor는 "한 수단의 승인/취소"만 안다.
- 조합 규칙·순서·보상은 **Orchestrator의 단독 책임** — 복합 결제 로직이 수단별 클래스에 새지 않도록.
- 수단 추가 시 Processor 구현체 1개 추가로 끝난다 (OCP).

## 인터페이스

```java
public interface PaymentProcessor {
    PaymentMethod method();
    ProcessorResult approve(PaymentContext ctx, long amount);
    void cancel(String transactionId, PaymentContext ctx, long amount);
}
```

## 조합 검증 규칙

| 규칙 | 위반 시 |
|------|---------|
| 수단 목록 비어 있으면 안 됨 | 400 INVALID_PAYMENT_COMBINATION |
| CREDIT_CARD와 Y_PAY 동시 포함 금지 | 400 INVALID_PAYMENT_COMBINATION |
| Y_POINT 포함 → `0 < pointAmount ≤ 결제 금액` | 400 |
| Y_POINT 단독 → `pointAmount == 결제 금액` | 400 |
| Y_POINT 미포함 → `pointAmount == 0` | 400 |

검증은 멱등성 점유 **이전**에 수행 — 형식이 틀린 요청은 상태 기록 없이 즉시 거절.

## 금액 분배와 실행 순서

```
externalAmount = 결제금액 - pointAmount

1) pointAmount > 0     → Point 차감 (자사 DB, 조건부 UPDATE: balance >= ? 일 때만)
2) externalAmount > 0  → Card 또는 YPay 승인 (외부 게이트웨이)
```

**포인트를 먼저 차감하는 이유**: 포인트는 자사 DB라 차감·환불이 같은 시스템 안에서 확실히 보상된다.
외부 승인 먼저 → 포인트 차감 실패 시 "외부 결제 취소"라는 더 불확실한 보상이 필요해진다.
**보상이 쉬운 쪽을 먼저 실행하고, 보상이 어려운 쪽을 마지막에 둔다.**

- 포인트 차감: `UPDATE user_points SET balance = balance - ? WHERE user_id = ? AND balance >= ?`
  (0 rows → INSUFFICIENT_POINT. read-modify-write 금지 → 04)
- transactionId: 외부 승인 시 게이트웨이 발급값, 포인트 단독은 서버 생성(`PT-` 접두). `payments.transaction_id UNIQUE`.

## 결제 실패 / 부분 실패 보상 흐름

### 보상의 핵심 원칙: effectively-once

보상 트리거는 두 곳이다 (결제 실패 즉시 경로 + Recovery Job 타임아웃 경로).
같은 건이 두 경로에서 각각 실행되면 포인트 이중 환불 → 재고 이중 복구 → phantom stock 위험.

**해결: 상태 전이 CAS(Compare-And-Set)로 보상 권리를 한 경로에만 부여**

```java
// 보상 진입 전 항상 이 가드를 먼저 실행
int updated = bookingRequestRepository.compareAndSetStatus(
    requestId, PAYMENT_FAILED, COMPENSATING  // 또는 PENDING → COMPENSATING
);
if (updated == 0) return;  // 이미 다른 경로가 진입함 → 스킵
// 여기까지 온 스레드만 보상 권리를 가짐
```

```sql
-- 구현: DB row-level lock이 분산 환경의 직렬화 역할
UPDATE booking_requests
SET status = 'COMPENSATING'
WHERE id = ? AND status = 'PAYMENT_FAILED'
-- affected=0 → 이미 처리됨 → 스킵
```

### 결제 실패 보상 단계 (Saga 패턴)

```
1) CAS 진입:
   UPDATE booking_requests SET status='COMPENSATING'
   WHERE id=? AND status='PAYMENT_FAILED'
   → 0 rows면 이미 처리 중 → 리턴

2) 포인트 환불 (멱등):
   INSERT INTO point_history(order_id, type='REFUND', amount, ...)
   -- UNIQUE(order_id, type) → 재실행돼도 duplicate key → 무시
   → points_refunded = true (booking_requests 컬럼)

3) 재고 복구:
   DB reserved_quantity를 available_quantity로 되돌린다.
   Redis INCR은 best-effort로 1회만 시도한다.
   성공하면 stock_restore_status = SYNCED
   실패하거나 성공 여부가 불명확하면 stock_restore_status = NEEDS_SYNC

   핵심 원칙:
   - Redis INCR을 at-least-once로 재시도하지 않는다.
   - 중복 INCR은 phantom stock을 만들 수 있으므로 초과판매 위험 방향이다.
   - Redis 복구가 불명확하면 일시적 under-sell을 수용하고, DB 기준 StockSyncService로 회복한다.

4) 포인트 환불과 재고 복구 상태 기록이 끝난 뒤 종결:
   UPDATE booking_requests SET status='FAILED'
   -- 종결은 맨 마지막 — 중간 크래시 시 COMPENSATING으로 남아 Recovery Job이 이어받음
```

### 케이스별 처리

| 케이스 | 처리 |
|--------|------|
| 포인트 부족 | 0 rows → INSUFFICIENT_POINT, 외부 수단 미실행, 재고 보상 |
| 카드 한도 초과 (외부 거절) | 위 보상 흐름 전체 실행 |
| 포인트 차감 성공 + 카드 실패 | 동일. 포인트 환불이 2)에서 멱등하게 처리됨 |
| 포인트 환불 자체 실패 | COMPENSATING 상태 유지 → Recovery Job이 재시도 |
| Redis 재고 복구 실패/불명확 | `stock_restore_status=NEEDS_SYNC`, 중복 INCR 금지, stock sync 대상 |
| Recovery Job도 반복 실패 | ERROR 로그 + 수동 개입 대상 |

### 포기한 것 (문서로만)

- **포인트 차감을 확정 TX1 안으로**: 보상이 줄지만 "외부 승인 성공 후 TX1에서 포인트 부족 발견 → 외부 취소" 경로가 생김. 외부 취소가 더 불확실하므로 기각.
- **부분 취소/부분 환불**: 범위 외. 환불은 전액 기준으로만.
- **in-doubt 정산 / DLQ**: 과설계. 수동 개입 로그로 대체.

## 포인트 차감 트랜잭션 경계

BookingService/PaymentOrchestrator 전체에는 트랜잭션을 열지 않는다.
외부 PG 호출 동안 DB 커넥션을 잡지 않기 위해서다.

다만 포인트 차감은 자사 DB 변경이므로 `PointPaymentProcessor` 내부에서 짧은 독립 트랜잭션으로 수행한다.

- 포인트 차감: `user_points` 조건부 UPDATE + `point_history(type='USE')` 기록
- 외부 결제 실패: `point_history(type='REFUND')` 기록 + 포인트 환불
- `point_history(booking_request_id, type)` UNIQUE로 USE/REFUND 중복 반영을 막는다.

## 시뮬레이터 규칙 (결정적)

테스트 재현성을 위해 랜덤 실패 대신 입력값으로 성패를 결정한다.

| 입력 | 동작 |
|------|------|
| 카드번호 끝자리 `0000` | 승인 거절 (CARD_DECLINED) |
| YPay 토큰 `FAIL` | 승인 거절 (YPAY_DECLINED) |
| 그 외 | 승인 (transactionId 발급) |

---

## 확정 사항 요약

- [x] **Strategy Pattern**: PaymentProcessor 인터페이스 + Orchestrator 조합 관리
- [x] **포인트 선차감**: 보상이 쉬운 쪽 먼저. 포인트는 자사 DB라 환불 확실
- [x] **effectively-once 보상**: CAS 상태 전이 + unique constraint로 재시도 최종 효과 중복 반영 방지
- [x] **보상 단계 멱등**: point_history UNIQUE, Redis 복구는 `stock_restore_status`로 추적, 종결은 맨 마지막
- [x] **크래시 안전 방향**: Redis INCR 중복 재시도 금지. 불명확하면 NEEDS_SYNC로 남기고 DB 기준 sync
