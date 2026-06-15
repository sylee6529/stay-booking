# 05. 멱등성 설계

> 상태: **확정**

## 문제

타임아웃 재시도, 더블클릭, 게이트웨이 재전송으로 동일 결제 요청이 중복 유입된다.
분산 서버이므로 로컬 상태로는 차단 불가. **중복이 뚫리면 금전 사고(이중 결제)**다.

## 키 설계

- 클라이언트가 `Idempotency-Key` HTTP 헤더로 전달 (주문서 진입 시 생성한 UUID 가정).
- 서버는 키의 형식(길이 ≤ 64)만 검증. 키 생성 책임은 클라이언트에 있다.
- 키 누락 시 400 거절 (멱등성 없는 결제 요청을 허용하지 않는다).

## 검토한 대안

| 대안 | 방식 | 한계 | 판정 |
|------|------|------|------|
| A. Redis SETNX 단독 | 키 선점으로 차단 | Redis 유실/만료/장애 시 중복 통과. 금전 사고를 캐시에 맡길 수 없음 | 기각 |
| B. DB unique 단독 | `UNIQUE(user_id, idempotency_key)` | 확실하지만 00시 중복 폭주가 전부 DB INSERT로 도달 | 기각 |
| C. Redis admission + DB unique 진실 | 매진 초과분은 DB 전 차단, DB unique가 최종 중복 방어 | admission orphan/sync 처리 필요 | **채택** |

## 채택안 상세 (C)

```
[1-a] Redis GET idem:{userId}:{key}
      → 완료 캐시 존재: 기존 응답 재생

[1-b] Redis admission Lua
      - admission:{userId}:{key} 중복 확인
      - stock:{productId} > 0 확인
      - 최초 admission이면 admission 키 SET + stock DECR를 원자 실행
      → SOLD_OUT이면 DB 접근 없이 409

[1-c] DB INSERT booking_requests
      UNIQUE(user_id, idempotency_key)
      request_hash 저장
      → unique 충돌: 기존 요청 조회 후 request_hash 검증 + 멱등 재생

[1-d] 완료 후 Redis SET idem:{userId}:{key} (TTL 24h, best-effort)
```

핵심 순서: **Redis admission이 DB INSERT보다 먼저다.**

- 그래야 매진 초과 요청이 `booking_requests` INSERT까지 가지 않는다.
- DB unique는 Redis admission race나 Redis 캐시 만료 후 재요청을 잡는 최후 방어선이다.
- admission 성공 후 DB INSERT 전에 앱이 죽으면 Redis 재고가 일시적으로 줄어든다.
  이 orphan admission은 초과판매가 아니라 under-sell 위험이므로, admission TTL과 DB 기준 stock sync로 회복한다.

## 멱등키 상태 3분류

| 상태 | 의미 | 재요청 응답 |
|------|------|------------|
| `IN_PROGRESS` | 처리 중 (reservation/lease 유효) | 409 + `{"status":"IN_PROGRESS"}` + `Retry-After` |
| `SUCCEEDED` | 완료 | 200 + 예약 결과 재생 |
| `BUSINESS_FAILED` | 비즈니스 실패 (매진, 카드 거절 등) | 원 실패 응답 재생 |

**in-doubt는 어느 쪽으로도 굳히지 않는다.**
시스템 오류/타임아웃으로 결과 불명인 건은 `IN_PROGRESS`로 남기고 Recovery Job이 정착.
"처리 중"을 `BUSINESS_FAILED`로 재생하면 실제로는 성공한 결제를 실패로 알리는 사고가 난다.

## 실패 키 재시도 정책 (Stripe 방식)

**같은 키 = 항상 같은 응답.** 키는 "요청"에 묶이지 "결과"에 묶이지 않는다.

- 완료된 결과는 성공이든 비즈니스 실패든 그대로 재생한다.
- 진짜 재시도(다른 카드로, 포인트 충전 후 등)는 Checkout에서 새 토큰을 받아 **새 키**로 한다.
- 실패 시 키를 자동 해제하면 "실패 후 재시도" 중 또 중복이 발생 → 더블 서밋 위험.

## request_hash 정책

같은 멱등키는 같은 요청 본문에만 사용할 수 있다.
서버는 멱등성 점유 시 정규화한 요청 본문으로 `request_hash`를 계산해 DB에 저장한다.

동일 `userId` + 동일 `Idempotency-Key` 재요청 시:

| 조건 | 응답 |
|------|------|
| `request_hash` 동일 + 완료 | 원 응답 재생 |
| `request_hash` 동일 + 진행 중 | 409 REQUEST_IN_PROGRESS |
| `request_hash` 다름 | 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD |

이 정책이 없으면 같은 키로 `productId`나 `pointAmount`를 바꿔 보낸 요청에 기존 응답을 재생하는 사고가 생긴다.

## IN_PROGRESS lease 메커니즘

고아 IN_PROGRESS 문제: 처리 중 서버가 죽으면 키가 영원히 IN_PROGRESS로 남아 클라이언트가 루프.

```
step A 커밋 시:
  booking_requests.lease_expires_at = now() + PG_TIMEOUT + 여유(예: 10s)

Recovery Job 주기 스캔:
  WHERE status = 'APPROVING' AND lease_expires_at < now()
  → PG inquiry() 호출
    승인 확인됨 → step C부터 재개 (APPROVED + pg_tx_id)
    미승인 확인됨 → Saga 보상 → FAILED
    PG도 모름    → 보상 → FAILED (안전 방향)
```

이 lease + recovery가 있어야 클라이언트 계약이 닫힌다:
**backoff 재시도만 하면 유한 시간 안에 반드시 종결 응답을 받는다.**

## TTL 정책

- `idem:{key}` Redis TTL: **24h**
- DB UNIQUE 제약은 만료 없음 — **영구 보증**
- Redis TTL의 유일한 역할: 메모리 관리. 만료 = "캐시 미스 → DB 조회"일 뿐 정합성과 무관
- 제약: TTL ≫ Recovery Job 최대 정산 윈도우 (24h는 충분)

"DB UNIQUE가 영구 보증, Redis TTL은 성능 최적화"

## 멱등성이 지키는 범위

- 같은 키 → `booking_requests` 1행 → 예약 ≤ 1행, 결제 ≤ 1행
  (`bookings.booking_request_id`, `payments.booking_request_id`도 unique → 복구 재시도가 중복을 만들지 않는 2차 방어)
- 다른 키로 같은 사용자가 같은 상품을 두 번 사는 것은 막지 않는다 (정상 구매로 간주).

## Redis 장애 시

멱등성 캐시(GET/SET) 실패는 무시하고 진행해도 DB unique가 방어한다.
단, admission Lua는 재고 부하 차단의 핵심이므로 실패하면 Fail-Closed로 거절한다 (→ 07).

---

## 확정 사항 요약

- [x] **Redis admission 먼저**: 매진 초과 요청을 DB INSERT 전에 차단
- [x] **DB UNIQUE 최후 방어**: `UNIQUE(user_id, idempotency_key)`로 중복 요청 영구 보증
- [x] **request_hash**: 같은 키로 다른 payload를 보내면 409로 거절
- [x] **상태 3분류**: IN_PROGRESS / SUCCEEDED / BUSINESS_FAILED. in-doubt는 굳히지 않음
- [x] **실패 키 1회용**: 같은 키 = 항상 같은 응답. 재시도는 새 키로
- [x] **lease + Recovery Job**: 고아 IN_PROGRESS를 유한 시간 안에 정착. 클라이언트 계약 닫힘
- [x] **TTL 24h**: DB UNIQUE가 영구 보증, Redis TTL은 성능
