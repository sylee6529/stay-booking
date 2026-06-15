# 03. 예약 처리 흐름

> 상태: **확정**

## 전체 흐름

```
POST /api/bookings  (Idempotency-Key 헤더)
  │
  ▼
[0] 검증 (트랜잭션 없음, 읽기만)
  │   상품 존재 / open_at 도래 / 결제 조합 유효성(Card+YPay 금지 등)
  │   금액 = 상품 가격 (서버 계산)
  ▼
[1] Redis admission gate                            → 매진이면: DB 접근 없이 409
  │   Redis Lua:
  │     - idem:{userId}:{key} 완료 캐시 확인
  │     - admission:{userId}:{idempotencyKey} 중복 확인
  │     - stock:{productId} > 0 이면 admission 기록 + DECR
  │   Redis 장애/키 부재: 503 Fail-Closed
  ▼
[2] DB 요청 기록 + 재고 예약 (짧은 tx)
  │   INSERT booking_requests (STOCK_RESERVED, request_hash, reservation_expires_at)
  │   UPDATE promotion_products
  │      SET available_quantity = available_quantity - 1,
  │          reserved_quantity = reserved_quantity + 1
  │    WHERE id = ? AND available_quantity > 0
  │   unique 충돌: 기존 요청 조회 후 request_hash 검증 + 멱등 재생
  │   DB reserve 0건: Redis drift로 보고 REJECTED(SOLD_OUT_DB), Redis 보상 대신 NEEDS_SYNC
  ▼
[3] 결제 — Orchestrator (트랜잭션 없음)
  │
  ├─ step A (tx, 즉시 커밋): status = APPROVING + lease_expires_at 기록
  │   ← PG 호출 "전에" 마커. 크래시 시 Recovery Job이 "in-doubt" 판정 근거
  │
  ├─ step B (Booking 전체 트랜잭션 없음): 결제 처리
  │   포인트 선차감(짧은 독립 tx) → 외부 수단(Card|YPay) 승인(트랜잭션 없음)
  │   → 실패: DB reserved release + Redis INCR best-effort, Saga 보상 흐름 → PAYMENT_FAILED(사유), 422
  │
  ├─ step C (tx, 즉시 커밋): status = APPROVED + pg_tx_id 기록
  │   ← "결제 승인됨"이 영속화됨. Recovery Job은 여기서부터 확정만 재시도
  │
  └─ step D (tx): 재고 확정 + status = PAID
       ① UPDATE promotion_products
          SET reserved_quantity = reserved_quantity - 1,
              sold_quantity = sold_quantity + 1
          WHERE id = ? AND reserved_quantity > 0
       ② INSERT bookings
       ③ INSERT payments
       ④ booking_request → CONFIRMED
       → 조건부 UPDATE 0건: SOLD_OUT_DB, 결제 취소
       → 그 외 DB 실패: 결제 취소 + Redis 보상, 보상 실패 시 RECOVERY_NEEDED
  ▼
200 OK { bookingId, status: CONFIRMED }
```

## REQUIRES_NEW를 쓰지 않는 이유

Orchestrator 메서드 자체에 `@Transactional`을 붙이지 않는다.
각 step이 독립된 `@Transactional` 메서드를 순차 호출하는 구조다.

```
Orchestrator (non-transactional)
  → stepA()   @Transactional  → 커밋 → 커넥션 반납
  → stepB()   Booking TX 없음 (포인트는 내부 짧은 tx, 외부 PG는 tx 없음)
  → stepC()   @Transactional  → 커밋 → 커넥션 반납
  → stepD()   @Transactional  → 커밋 → 커넥션 반납
```

**REQUIRES_NEW의 문제**: 바깥 트랜잭션이 살아 있는 채로 두 번째 커넥션을 추가 획득.
1000 TPS에서 커넥션이 2N개 필요 → 풀 압박 두 배 + 중첩 트랜잭션 버그의 온상.

순차 커밋 방식은 커넥션을 동시에 잡지 않고 하나씩 쓰고 반납한다.
같은 목적("각 단계를 durable하게")을 달성하면서 커넥션 압박이 없다.

## 상태 머신 (`booking_requests.status`)

```
RECEIVED
  │
  ▼
STOCK_RESERVED
  │
  ▼
APPROVING ───────────────────────────────────────────────────────────┐
  │                                                                   │ lease 만료
  ▼                                                                   ▼
APPROVED ──────────────────────────────────────────────┐     Recovery Job
  │                                                     │     inquiry() → 분기
  ▼                                                     │
PAID(=CONFIRMED)                                        │
                                                        │
PAYMENT_FAILED ──▶ COMPENSATING ──▶ FAILED             │
                                                        │
RECOVERY_NEEDED ←──────────────────────────────────────┘
  │
  ▼ (recovery job)
CONFIRMED | COMPENSATED
```

| 상태 | 의미 | 종결 여부 |
|------|------|----------|
| RECEIVED | DB 요청 기록 완료. 재고 예약 전이면 짧게만 머무름 | 진행 중 |
| STOCK_RESERVED | Redis admission + DB 재고 예약 완료 | 진행 중 |
| APPROVING | PG 호출 시작 전 마커 (lease 있음) | 진행 중 |
| APPROVED | PG 승인 확인됨, pg_tx_id 기록됨 | 진행 중 |
| CONFIRMED | 예약 확정 | **종결** |
| PAYMENT_FAILED | 결제 실패 (사유 포함) | 진행 중 (보상 필요) |
| COMPENSATING | 보상 진행 중 (CAS 가드 통과) | 진행 중 |
| FAILED | 보상 완료 | **종결** |
| REJECTED | 매진/미오픈/게이트 장애 (보상 불요) | **종결** |
| RECOVERY_NEEDED | 확정·보상 모두 실패, 수동 개입 대기 | 진행 중 |

- 모든 상태 전이는 DB에 영속화된다. **어느 시점에 크래시해도 상태가 "어디까지 진행됐는지"를 증언**.
- 종결 상태가 아닌 것은 모두 Recovery Job 스캔 대상.

## Recovery Job 진입 조건 (→ 07)

| 조건 | 처리 |
|------|------|
| `STOCK_RESERVED` + `reservation_expires_at` 만료 | PG 호출 전 예약 토큰 만료 → Redis INCR best-effort / 실패·불명확 시 NEEDS_SYNC |
| `APPROVING` + `lease_expires_at` 만료 | PG inquiry() → 승인됨: step C부터 재개 / 미승인: 보상 |
| `APPROVED` + pg_tx_id 있음 + 60s 경과 | step D만 재시도 (확정) |
| `COMPENSATING` 체류 | Saga 보상 2~4단계 이어받기 |
| `RECOVERY_NEEDED` | 확정·보상 재시도, 반복 실패 시 ERROR 로그 |

## 트랜잭션 경계

| 구간 | 경계 | 이유 |
|------|------|------|
| [0] 검증 | 트랜잭션 없음 (읽기) | 쓰기 없음 |
| [1] Redis admission | 트랜잭션 밖 | 매진 초과분을 DB write 전에 탈락시킴 |
| [2] DB 요청 기록 + 재고 예약 | 독립 tx, 즉시 커밋 | admission 성공 요청만 DB에 기록하고, DB 재고 모델도 예약 상태로 반영 |
| step A | 독립 tx, 즉시 커밋 | APPROVING 마커는 PG 호출 전에 영속화 필요 |
| step B | Booking 전체 tx 없음 | 포인트 차감은 짧은 독립 tx, 외부 호출은 절대 tx 안에서 X |
| step C | 독립 tx, 즉시 커밋 | pg_tx_id를 확정 전에 영속화. Recovery Job의 "APPROVED" 판정 근거 |
| step D | 단일 tx | 재고 확정 + 예약 + 결제 기록 + 상태 전이는 전부 성공 or 전부 롤백 |
| 실패/보상 기록 | 독립 tx | 실패 상태는 본 트랜잭션 롤백과 무관하게 남아야 함 |

## 멱등 재생 정책 (→ 05, 10)

| 기존 상태 | 재요청 응답 |
|-----------|------------|
| CONFIRMED | 200 + 원 응답 재생 |
| IN_PROGRESS (RECEIVED ~ APPROVED) | 409 `REQUEST_IN_PROGRESS` + `{"status":"IN_PROGRESS"}` + `Retry-After` |
| APPROVING (lease 만료 → in-doubt) | Recovery Job이 정착시킬 때까지 IN_PROGRESS로 응답 |
| FAILED / REJECTED | 원 실패 응답 재생 (키는 1회용 — 재시도는 새 키로) |

클라이언트 계약: backoff 재시도만 하면 **유한 시간 안에 반드시 종결 응답**을 받는다.
IN_PROGRESS가 정상 진행인지 dead인지 클라이언트가 판단할 필요 없음 — 서버가 lease로 보장.

---

## 확정 사항 요약

- [x] **Redis admission 선행**: 매진 요청은 DB INSERT 전에 차단
- [x] **Orchestrator 비트랜잭션**: REQUIRES_NEW 없이 각 step을 독립 tx로 순차 실행
- [x] **STOCK_RESERVED 만료 복구**: PG 호출 전 선점 후 크래시도 recovery 대상으로 처리
- [x] **APPROVING 마커**: PG 호출 전 lease와 함께 커밋 — in-doubt 복구의 근거
- [x] **APPROVED + pg_tx_id**: PG 승인 후 즉시 커밋 — Recovery Job이 확정만 재시도 가능
- [x] **상태 머신**: COMPENSATING 중간 상태 포함, lease 만료 경로 포함
- [x] **멱등 재생**: 완료=200 재생, 진행중=409+status, 실패=원 응답 재생
