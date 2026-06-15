# 04. 동시성 제어와 재고 설계

> 상태: **확정**

## 문제

분산 서버 N대에서 동일 상품 재고를 동시에 차감한다. 요구: **oversell 0건** + **DB 과부하 방지**.

피크 규모: 500~1000TPS × 1~5분 = 최대 30만 요청, 재고 10개.
JVM 로컬 락은 분산 환경에서 무의미하므로, 경합 제어 지점은 모든 서버가 공유하는 Redis 또는 MySQL뿐이다.

## 검토한 대안

| 대안 | 방식 | 장점 | 단점 | 판정 |
|------|------|------|------|------|
| A. DB 비관적 락 | `SELECT ... FOR UPDATE` 후 차감 | 구현 단순, 정합성 확실 | 30만 요청이 한 row의 lock 대기열에 직렬화 → 커넥션 풀 고갈 → 전 서비스 연쇄 장애 | 기각 |
| B. DB 조건부 UPDATE 단독 | `UPDATE ... SET available=available-1 WHERE available>0` | lock 대기 없음, 원자적 | 30만 요청 전부 DB 직격 → 쓰기 부하 그대로 | 기각 (단독으로는) |
| C. Redis Lua 선점 + B를 최후 방어선으로 | Redis가 탈락분 흡수, DB가 최종 차단 | DB에는 ~재고 수량만 도달. Redis가 틀려도 DB가 oversell 차단 | 두 저장소 보상·동기화 로직 필요 | **채택** |
| D. 메시지 큐 직렬화 | 큐 적재 후 단일 컨슈머 처리 | 완전 직렬화 | 동기 응답 불가. 재고 10개를 위해 30만 메시지 인프라 운영 — 비율 맞지 않음 | 기각 |

### 채택 근거 (C)

- 30만 요청 중 "어차피 실패할" 29만 9990건을 Redis가 밀리초 안에 돌려보낸다. DB에는 ~10건만 도달.
- Redis 값이 틀려도(장애 복구 직후, 보상 누락) DB의 `WHERE available_quantity > 0`이 **물리적으로** 초과 예약을 차단한다.
- **Redis는 "990개를 빠르게 거절하는 부하 차단기"이고, 정합성의 최종 책임은 DB에 있다.**

## Redis 선점 설계

### Lua 스크립트 (원자 실행)

```lua
-- KEYS[1] = stock:{productId}
-- KEYS[2] = admission:{userId}:{idempotencyKey}
-- ARGV[1] = admission ttl seconds

if redis.call('EXISTS', KEYS[2]) == 1 then
  return -3                                -- 같은 key의 admission 중복
end

local stock = redis.call('GET', KEYS[1])
if stock == false then return -2 end          -- 키 부재: 명시적 실패 (Fail-Closed)
if tonumber(stock) <= 0 then return -1 end    -- 매진
redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
return tonumber(stock) - 1                    -- 선점 성공, 남은 수량
```

- `GET` 후 `DECR`을 애플리케이션에서 두 번 호출하면 사이에 다른 요청이 끼어든다.
  Lua는 Redis 단일 스레드에서 원자 실행되므로 admission 중복 확인과 check-and-decrement가 한 동작이 된다.
- `admission:{userId}:{idempotencyKey}`는 같은 키 재요청이 Redis 재고를 두 번 차감하지 못하게 하는 짧은 TTL 키다.
  DB 기록 전 크래시로 orphan admission이 생기면 TTL 만료와 DB 기준 stock sync가 under-sell을 회복한다.
- **키 부재(-2)를 매진이나 통과로 해석하지 않는다.** "키 없음 → 무한 재고"로 오해되는 사고를 차단하고,
  미초기화/유실 상태에서는 Fail-Closed로 거절한다 (→ 07).
- 보상(결제 실패/예약 만료 시): Redis `INCR`은 best-effort 1회만 시도한다.
  실패하거나 결과가 불명확하면 중복 `INCR` 대신 `stock_restore_status=NEEDS_SYNC`로 남기고 DB 기준 sync로 회복한다.

### 키 수명

- `stock:{productId}`는 TTL 없음(만료로 인한 "키 부재 = 판매 중단" 사고 방지).
- 초기화/재계산은 `StockSyncService`가 DB 기준으로 수행 (아래).

## DB 최후 방어선

DB의 재고 모델은 같은 상품 row 안에서 세 값을 분리한다.

```text
available_quantity + reserved_quantity + sold_quantity = total_quantity
```

- `available_quantity`: 아직 누구에게도 선점되지 않은 수량
- `reserved_quantity`: Redis admission 이후 결제/확정 진행 중인 수량
- `sold_quantity`: 최종 확정된 예약 수량

Redis admission이 성공하면 DB에도 짧은 트랜잭션으로 예약 상태를 반영한다.

```sql
UPDATE promotion_products
SET available_quantity = available_quantity - 1,
    reserved_quantity = reserved_quantity + 1
WHERE id = ? AND available_quantity > 0;
```

결제 성공 후 확정 트랜잭션(TX1)에서는 예약 수량을 판매 수량으로 이동한다.

```sql
UPDATE promotion_products
SET reserved_quantity = reserved_quantity - 1,
    sold_quantity = sold_quantity + 1
WHERE id = ? AND reserved_quantity > 0;
```

결제 실패/만료 시에는 예약 수량을 가용 수량으로 되돌린다.

```sql
UPDATE promotion_products
SET reserved_quantity = reserved_quantity - 1,
    available_quantity = available_quantity + 1
WHERE id = ? AND reserved_quantity > 0;
```

**이 문장들이 원자적인 이유:**
- 읽기와 쓰기가 한 UPDATE 안에서 실행된다.
- `WHERE available_quantity > 0` 또는 `reserved_quantity > 0` 조건 검사와 갱신이 동시에 발생한다.
- InnoDB는 UPDATE 시 해당 row에 배타 락 → 두 트랜잭션이 동시 실행돼도 한쪽은 락 해제 후 재검사 → lost update 없음

**JPA read-modify-write를 쓰면 안 되는 이유:**
```java
// 위험: T1, T2 둘 다 available=1을 읽고 → 둘 다 0으로 씀 → 중복 예약 가능
Product p = repository.findById(id);
p.setAvailableQuantity(p.getAvailableQuantity() - 1);
repository.save(p);
```

- reserve affected rows = 0 → Redis drift 또는 DB 기준 매진으로 판정.
- 추가 안전망: DDL에 `CHECK (available_quantity + reserved_quantity + sold_quantity = total_quantity)`.

## 두 저장소의 정합성 (drift)

Redis 재고와 DB 재고는 보상 누락·장애로 어긋날 수 있다. 방향별 위험:

| drift 방향 | 원인 예 | 결과 | 방어 |
|-----------|---------|------|------|
| Redis < 실제 가용 재고 | 선점 후 보상(INCR) 실패 누적 | 덜 팔림 (판매 기회 손실) | reconciliation으로 복구 가능. 치명적이지 않음 |
| Redis > 실제 재고 | sync 시점 오류, 수동 조작 실수 | 초과 선점 시도 | **DB 조건부 UPDATE가 차단** → oversell은 발생하지 않음 |

### 동기화 원칙: DB → Redis 단방향

- 재고의 진실은 DB의 `available_quantity`로 고정한다.
- `StockSyncService`: DB의 `available_quantity`를 Redis 키에 **덮어쓴다**(SET). 몇 번을 실행해도 안전(멱등).
- 양방향 동기화는 "어느 쪽이 옳은가"를 판단 불가능하게 만들므로 두지 않는다.
- 실행 시점: 운영자 트리거(internal API), Redis 장애 복구 후, (데모 편의상) 앱 시작 시.
  - 진행 중 예약은 `reserved_quantity`에 반영되므로 판매 중 sync를 실행해도 그 수량이 Redis에 다시 열리지 않는다.

### SOLD_OUT_DB 시 Redis를 보상하지 않는 이유

Redis 선점은 통과했는데 DB UPDATE가 0건이면, DB(진실)가 소진을 선언한 것 → **Redis 값이 틀린 것**이다.
이때 INCR로 되돌리면 틀린 값을 더 키운다. 보상 대신 sync 대상으로 표시하고 로그를 남긴다.

---

## 확정 사항 요약

- [x] **C안 채택**: Redis Lua 선점 + DB 조건부 UPDATE 이중 방어
- [x] **DB 원자 UPDATE**: reserve/confirm/release를 조건부 UPDATE 단일 문장으로 처리. JPA read-modify-write 금지
- [x] **`stock:` 키 TTL 없음**: 만료로 인한 "키 부재 = 판매 중단" 사고 방지
- [x] **SOLD_OUT_DB 시 Redis 미보상**: DB가 소진을 선언한 것이므로 Redis 값이 틀린 것 → sync 대상
- [x] **StockSync 기준**: Redis 재고는 DB `available_quantity`로 덮어쓴다
- [x] **잔여 수량 노출**: Redis 값은 근사치. 정확한 값 노출이 필요하면 DB 조회 (성능 별도 검토)
