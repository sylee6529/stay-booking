# 10. API 명세

> 상태: **확정**

## 공통

- Base: `/api`
- 인증 없음 (가정 A3 → 01). `userId`를 파라미터/본문으로 전달.
- 에러 응답 공통 형식:

```json
{
  "code": "SOLD_OUT",
  "message": "재고가 모두 소진되었습니다.",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

`traceId` = 멱등키(또는 요청 수신 시 생성한 requestId). 전 흐름 로그와 연결 (→ 08).

---

## 1. Checkout — 주문서 조회

```
GET /api/checkout?productId={id}&userId={id}
```

- 상품 정보, 가격, 체크인/체크아웃, 사용자 포인트 잔액, 프로모션 오픈 여부를 반환한다.
- **재고를 차감하지 않는다** (조회 전용).
- 남은 수량은 노출하지 않는다 (정책 — 변경 시 필드 추가).

응답 200:

```json
{
  "productId": 1,
  "name": "제주 오션뷰 스테이 - 자정 오픈 특가",
  "price": 150000,
  "checkinDate": "2026-07-01",
  "checkoutDate": "2026-07-02",
  "open": true,
  "pointBalance": 50000
}
```

- `pointBalance`: 포인트 미사용 사용자(user_points 행 없음)는 0으로 응답.

에러: 404 `PRODUCT_NOT_FOUND`

---

## 2. Booking — 예약 생성

```
POST /api/bookings
Idempotency-Key: {클라이언트 생성 UUID}   ← 필수. 누락 시 400
```

요청:

```json
{
  "userId": 1,
  "productId": 1,
  "paymentMethods": ["CREDIT_CARD", "Y_POINT"],
  "pointAmount": 30000,
  "cardNumber": "4111-1111-1111-1234",
  "ypayToken": null
}
```

- `paymentMethods`: `CREDIT_CARD` | `Y_PAY` | `Y_POINT` 조합 (규칙 → 06)
- 결제 금액은 서버가 상품 가격으로 계산 — 요청에 금액 필드 없음
- `cardNumber`는 CREDIT_CARD 포함 시, `ypayToken`은 Y_PAY 포함 시 필수

응답 200 (확정):

```json
{
  "bookingId": 42,
  "bookingRequestId": 7,
  "status": "CONFIRMED",
  "paidAmount": 150000,
  "pointAmount": 30000
}
```

### 에러 코드 표

| HTTP | code | 의미 / 발생 지점 |
|------|------|------------------|
| 400 | INVALID_REQUEST | 필드 누락, Idempotency-Key 누락 |
| 400 | INVALID_PAYMENT_COMBINATION | Card+YPay 혼용, 포인트 금액 규칙 위반 |
| 400 | PROMOTION_NOT_OPEN | open_at 미도래 |
| 404 | PRODUCT_NOT_FOUND | |
| 409 | SOLD_OUT | Redis 게이트 매진 또는 DB 최후 방어선 차단 (내부 SOLD_OUT_DB는 로그로만 구분) |
| 409 | REQUEST_IN_PROGRESS | 같은 키의 요청이 처리 중 |
| 409 | IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD | 같은 userId/key로 다른 요청 본문 재사용 |
| 429 | RATE_LIMITED | 동일 사용자/클라이언트의 과도한 반복 요청. `Retry-After` 헤더 포함 |
| 422 | PAYMENT_DECLINED | 외부 결제 거절 |
| 422 | INSUFFICIENT_POINT | 포인트 부족 |
| 503 | STOCK_GATE_UNAVAILABLE | Redis 장애 — Fail-Closed (→ 07) |
| 500 | INTERNAL_ERROR | 기타 (RECOVERY_NEEDED 진입 포함) |

### 멱등 재생 정책

| 기존 상태 | 재요청 응답 | 설명 |
|----------|------------|------|
| CONFIRMED | 200 + 원 예약 응답 재생 | 완료된 결과를 그대로 재생 |
| IN_PROGRESS (RECEIVED ~ APPROVED) | 409 REQUEST_IN_PROGRESS | 바디에 `"status":"IN_PROGRESS"` + `Retry-After` 헤더 포함 |
| APPROVING (lease 만료, in-doubt) | 409 REQUEST_IN_PROGRESS | Recovery Job이 정착시킬 때까지 동일 응답. 내부 로그에는 IN_DOUBT로 남김 |
| FAILED / REJECTED | 원 실패 응답 재생 | 키는 1회용. 재시도는 새 키로 |

**완료 재생(200) vs 진행 중 충돌(409) 구분 근거**:
- 완료된 재생은 충돌이 아니라 결과 재전송 → 원 응답 코드 그대로
- IN_PROGRESS는 "아직 아니니 다시 물어봐" → 409 + `Retry-After`

클라이언트 계약: backoff 재시도만 하면 **유한 시간 안에 반드시 종결 응답**을 받는다.
IN_PROGRESS가 정상 진행인지 서버 크래시인지 클라이언트가 판단할 필요 없음 — lease + Recovery Job이 보장 (→ 05).

---

## 3. Internal — 운영용

```
POST /internal/products/{productId}/stock-sync
```

- DB 기준 잔여 수량으로 Redis 재고 키를 재계산(덮어쓰기). 멱등 (→ 04).
- Redis 장애 복구 후 판매 재개 절차에 사용.
- 인증 없음(범위 외) — 실서비스라면 내부망/권한 통제 전제.

---

## 확정 사항 요약

- [x] **에러 봉투**: `{code, message, traceId}`. traceId = 멱등키, 전 흐름 로그 연결
- [x] **SOLD_OUT_DB**: 클라이언트에는 SOLD_OUT으로 통합 노출. 내부 로그/상태에서는 구분 유지
- [x] **in-doubt 처리**: 외부 API는 REQUEST_IN_PROGRESS로 통일. 내부 로그/상태에서만 IN_DOUBT 구분
- [x] **완료 재생 200 vs 진행 중 충돌 409**: 명확히 구분
- [x] **request_hash 검증**: 같은 멱등키로 다른 payload가 오면 409
- [x] **409 바디**: `{"status":"IN_PROGRESS"}` + `Retry-After` 헤더 포함
- [x] **키 1회용**: 실패 응답 재생. 재시도는 새 키로 (Stripe 방식)
- [x] **Rate Limit**: 동일 사용자 반복 요청 보호 목적의 429 RATE_LIMITED + Retry-After
- [x] **PG 보호**: PG 호출에 Bulkhead + TimeLimiter + CircuitBreaker 적용 (→ 07)
