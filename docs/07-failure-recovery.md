# 07. 장애 및 복구 전략

> 상태: **확정**

## 설계 원칙

1. **외부 효과(결제)는 롤백 불가** → 보상(cancel)으로 짝을 맞춘다.
2. **모든 중간 상태는 크래시 전에 DB에 영속화** → 어떤 시점의 장애도 "스캔 + 재시도"로 수렴 가능.
3. **재시도는 멱등** → unique constraint, request_hash, CAS 상태 전이 덕분에 복구 재시도가 최종 효과를 중복 반영하지 않는다.

## 장애 매트릭스

흐름 단계별( → 03)로 장애 지점과 대응을 정의한다.

| # | 장애 지점 | 상태 증거 | 즉시 대응 | 최종 수렴 |
|---|-----------|----------|----------|----------|
| F1 | [2] Redis 장애 (선점 불가) | REJECTED(STOCK_GATE_UNAVAILABLE) | **Fail-Closed**: 503 거절 | Redis 복구 → stock sync → 판매 재개 |
| F2 | [2] 매진 | REJECTED(SOLD_OUT) | 409 | 종결 |
| F2-1 | Redis admission 후 DB 기록 전 크래시 | Redis admission key만 남음 | — (죽었으므로) | admission TTL 만료 + stock sync로 under-sell 회복 |
| F2-2 | STOCK_RESERVED 장기 체류 | `STOCK_RESERVED + reservation_expires_at 만료` | PG 호출 전이므로 결제 없음. 재고 release | FAILED/EXPIRED 또는 NEEDS_SYNC |
| F3 | [3] 결제 거절 (포인트 부족, 카드 거절 등) | PAYMENT_FAILED(사유) | 포인트 환불 + Redis INCR best-effort, 422 | 종결 또는 NEEDS_SYNC |
| F4 | [3] 복합 결제 부분 실패 | PAYMENT_FAILED | Orchestrator가 포인트 환불 후 F3과 동일 | 종결 |
| F5 | [3] Redis 보상(INCR) 실패/불명확 | `stock_restore_status=NEEDS_SYNC` + 보상실패 로그 | 중복 INCR 금지. 영향은 덜 팔림(under-sell) | stock sync가 회수 |
| F6 | [3]→[4] 사이 앱 크래시 (결제 승인 후) | **APPROVED + pg_tx_id** (선커밋됨) | — (죽었으므로) | recovery job이 감지 → 확정 재시도 |
| F7 | [4] DB 조건부 UPDATE 0건 (SOLD_OUT_DB) | REJECTED(SOLD_OUT_DB) | 결제 전체 취소. Redis 보상 안 함(→ 04) | 종결 + sync 대상 표시 |
| F8 | [4] DB 트랜잭션 실패 (기타) | PAYMENT_FAILED(DB_SAVE_FAILED) | 결제 취소 + Redis 보상 | 종결 |
| F9 | F8의 보상까지 실패 (DB·외부 동시 불능) | **RECOVERY_NEEDED** | 구조화 로그(ERROR) | recovery job이 재시도 |
| F10 | DB 전면 장애 | Redis admission 후 DB 기록 실패 가능 | 5xx + Redis 복구 불명확 시 NEEDS_SYNC | DB 복구 후 stock sync |

## Redis 장애 정책: Fail-Closed (확정)

### 검토한 모든 대안과 탈락 이유

| 대안 | 탈락 이유 |
|------|----------|
| Fail-Open | oversell 압력 + 30만 요청 DB 직격. 선택 불가 |
| DB Fallback (무방비) | 피크 중 장애 시 30만 요청이 DB 직격 → 커넥션 풀 고갈 → 연쇄 장애 |
| DB Fallback + 전역 Rate Limit | 전역 Rate Limit은 먼저 차단된 사용자가 불리 → **공정성 훼손**. "시스템이 임의로 일부를 차별"하는 구조 |
| DB Fallback + 로컬 캐시 | 분산 서버 2대가 각자 다른 캐시를 가짐 → **서버 간 상태 불일치** → 정합성 보장 불가 |
| 메시지 큐 버퍼 | 동기 응답 불가(`POST /booking`은 즉각 성공/실패 필요). 재고 10개를 위해 30만 메시지 인프라 — 비율 맞지 않음. 큐 도달 순서도 네트워크 속도에 의존 → 공정성 개선 없음 |
| 제한적 DB-only degraded mode | 평시/낮은 동시성에서는 가능하지만, 피크 기본 경로로 두면 설계가 복잡해짐. 부하테스트 결과가 있을 때만 운영 옵션으로 둔다 |
| **Fail-Closed** | **기본 정책으로 채택**. 아래 근거 참고 |

### Fail-Closed 채택 근거

Redis 장애 시 **공정성·정합성·가용성을 동시에 보장하는 방법은 없다.**

- 로컬 캐시 → 분산 환경 정합성 불가
- 전역 Rate Limit → 공정성 훼손
- DB Fallback → 30만 요청 직격 위험

피크 구간에서 세 가지가 모두 불가능하면 남는 기본 선택은 **"명시적 서비스 중단"**이다.
"Redis 없이 안전하게 처리할 방법이 검증되지 않았으므로 장애 중에는 정직하게 거절한다."

### 제한적 DB-only degraded mode (운영 옵션)

Redis 장애 중 무제한 DB fallback은 선택하지 않는다.
다만 부하테스트에서 검증된 동시성 한도 안에서는 DB-only degraded mode를 둘 수 있다.

- booking bulkhead 동시성 N개 제한
- user-level RateLimiter
- Hikari pending connection이 늘어나면 즉시 429/503
- DB 조건부 UPDATE로 `available_quantity > 0` reserve
- 성공 가능성을 보장하지 않고, 시스템 붕괴와 초과판매 방지가 목표

이 모드는 기본 구현 경로가 아니라, 부하테스트 결과를 README/DECISIONS.md에 붙인 뒤 켤 수 있는 운영 옵션이다.

### 이 결정의 한계 (명시)

- Redis가 가용성 관점 SPOF로 남는다.
- **근본 해법은 Redis 자체의 가용성을 높이는 것(Sentinel/Cluster).**
  이 프로젝트는 단일 Redis를 전제로 정책 계층만 구현하되, DECISIONS.md에 운영 전제로 명시한다.
- Redis 장애가 00시 피크 1~5분과 겹치면 기본 정책에서는 해당 회차 판매를 중단한다.
  제한적 degraded mode는 별도 부하테스트 근거가 있을 때만 허용한다.

### 적용 범위 및 복구 절차

- 적용: 재고 선점 연산 실패(타임아웃 포함), `stock:` 키 부재
- 멱등성 캐시 실패는 예외 — DB unique가 방어하므로 진행 가능 (→ 05)
- 복구 절차: Redis 복구 → `StockSyncService`(DB 기준 덮어쓰기, 멱등) → 판매 재개

## 보상 트랜잭션 설계 (Saga 패턴)

결제 실패 시 보상이 두 경로(즉시 경로 + Recovery Job)에서 중복 실행되면
포인트 이중 환불 → 재고 이중 복구 → phantom stock → oversell 위험이 생긴다.

### effectively-once 보상: CAS 상태 전이 가드

외부 효과를 엄밀히 "정확히 한 번만" 발생시킨다고 주장하지 않는다.
대신 각 단계에 idempotency key, DB unique constraint, CAS 상태 전이를 두어
재시도되어도 최종 효과가 한 번만 반영되도록 설계한다.

```sql
-- 보상 진입 전 반드시 이 UPDATE를 먼저 실행
UPDATE booking_requests
SET status = 'COMPENSATING'
WHERE id = ? AND status = 'PAYMENT_FAILED'
-- affected = 0 → 이미 다른 경로가 진입 → 스킵
```

DB row-level lock이 분산 환경의 직렬화 역할을 한다. 두 경로가 동시에 시도해도 한쪽만 1 row를 얻는다.

### 보상 단계 순서

```
1) CAS 진입 (위 UPDATE)
   → 0 rows → return

2) 포인트 환불 (멱등)
   INSERT INTO point_history(order_id, type='REFUND', amount, ...)
   UNIQUE(order_id, type) → 재실행돼도 duplicate key → 무시
   → booking_requests.points_refunded = true

3) 재고 복구
   Redis INCR은 best-effort로 1회만 시도한다.
   성공하면 booking_requests.stock_restore_status = 'SYNCED'
   실패하거나 성공 여부가 불명확하면 stock_restore_status = 'NEEDS_SYNC'

   [왜 중복 재시도하지 않는가]
     Redis INCR 성공 직후 앱이 크래시하면, Recovery Job은 성공 여부를 확실히 알 수 없다.
     이때 INCR을 다시 실행하면 phantom stock이 생겨 초과 선점 위험이 커진다.
     따라서 불명확한 경우에는 중복 INCR 대신 NEEDS_SYNC로 남기고,
     DB의 available_quantity 기준으로 Redis를 덮어쓰는 stock sync가 회복한다.

4) 종결 (모든 단계 완료 후)
   UPDATE booking_requests SET status = 'FAILED'
   → 종결은 맨 마지막. 중간 크래시 시 COMPENSATING으로 남아 Recovery Job이 이어받음
```

### Recovery Job이 보상 단계를 재실행할 때

- 2단계 재실행: `points_refunded=true`면 point_history INSERT 스킵 (UNIQUE로도 방어)
- 3단계 재실행: `stock_restore_status=NEEDS_SYNC`면 Redis INCR을 중복 재시도하지 않고 sync 대상으로 남긴다.
- 각 단계가 독립적으로 멱등하므로 어느 단계부터 재실행해도 안전

## Recovery Job

```
@Scheduled (주기: 30s)
대상:
  (a0) STOCK_RESERVED + reservation_expires_at 만료
  (a) APPROVED 인 채 60s 경과           ← F6: 결제 성공 후 확정 직전 크래시
  (b) COMPENSATING 상태 체류            ← 보상 도중 크래시 → 2~4단계 이어받음
  (c) RECOVERY_NEEDED                   ← F9: 확정 실패 + 보상까지 실패
처리:
  (a0): PG 호출 전 만료이므로 결제 조회 없음. DB reserved → available release 후 Redis INCR best-effort
        Redis 실패/불명확이면 NEEDS_SYNC
  (a): 확정 재시도 (TX1과 동일 로직, 멱등 — 이미 booking 있으면 그대로 CONFIRMED 마감)
       확정 불가(SOLD_OUT_DB 등) → 위 Saga 보상 흐름 실행 → FAILED
  (b): 위 보상 단계 2~4 이어받기 (각 단계 멱등)
  (c): 확정·보상 모두 실패 → ERROR 로그 → 다음 주기 재시도
       반복 실패 = 수동 개입 신호
```

- Job 자체가 죽어도 상태는 DB에 있으므로 재실행이 곧 복구다.
- 분산 서버 2대에서 동시 실행돼도 CAS 가드 + unique constraint들이 중복 처리를 차단한다.
- **stock-sync는 선택적 보완이 아닌 구조적 필수 요소다.** 보상 INCR이 Redis 장애로 누락된 경우,
  Redis가 복구된 후 sync 없이는 재고가 영구적으로 under-count된 채 남는다.
  보상이 "Redis-agnostic"하게 동작한다는 주장은 틀렸다.
- Outbox 미채택: 이 시스템에는 다운스트림 컨슈머가 없다. 상태 머신 + recovery job으로 직접 해결.

## 고가용성 보호 설정

Redis admission이 매진 초과 요청의 DB write를 크게 줄이지만, 설정값 부재로 인한 스레드 풀 고갈을 막기 위해 타임아웃을 명시한다.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        # 서버 2대 × 20 = 총 40 커넥션
      connection-timeout: 3000     # 3초. 기본 30초는 피크 중 스레드 풀 고갈로 직결
      max-lifetime: 600000
  data:
    redis:
      timeout: 2s                  # Fail-Closed 판정 속도. 길면 스레드가 대기로 점유됨
```

## Resilience4j 기반 자원 보호

Resilience4j는 재고 정합성이나 선착순 판정 장치가 아니다.
정합성은 Redis Lua + DB 조건부 UPDATE가 담당하고, Resilience4j는 외부 지연과 과도 요청이
애플리케이션 스레드를 고갈시키지 않도록 보호하는 역할만 맡는다.

- TimeLimiter: 외부 PG 시뮬레이터 호출이 오래 걸리면 빠르게 실패 처리한다.
- Bulkhead: PG 호출 동시 실행 수를 제한해 예약 API 전체 스레드 고갈을 막는다.
- CircuitBreaker: 짧은 시간 반복 실패가 발생하면 일정 시간 PG 호출을 차단해 후속 요청을 빠르게 실패시킨다.
- RateLimiter: 동일 사용자의 과도한 반복 요청을 Redis fixed-window로 429 제한한다.

RateLimiter는 공정성 보장 수단이 아니다. 전역 선착순 순서를 임의로 자르는 방식으로 쓰지 않고,
반복 클릭/자동 재시도 폭주가 서버 자원을 독점하지 못하게 하는 방어선으로만 사용한다.

### PG 보호 설정 기준

현재 값은 운영 최적값이 아니라, 로컬 단일 인스턴스 부하테스트에서 장애 격리 동작을 확인하기 위한 기준선이다.
운영에서는 PG SLA, API timeout, 서버 수, DB 커넥션 한도, 실제 실패율을 보고 재조정해야 한다.

| 설정 | 값 | 이유 |
|------|----|------|
| Bulkhead max concurrent calls | 20 | PG 호출 동시성을 Hikari pool 크기와 같은 수준으로 제한한다. PG 지연이 커졌을 때 외부 호출 대기가 애플리케이션 전체로 번지는 것을 막는다. |
| Bulkhead max wait duration | 0s | bulkhead가 가득 차면 기다리지 않고 바로 실패한다. 선착순 예약 API에서는 느린 대기보다 빠른 실패와 보상이 더 예측 가능하다. |
| TimeLimiter timeout | 2s | PG가 지연될 때 요청을 무기한 붙잡지 않고 결제 실패/보상 경로로 전환한다. 로컬 PG delay 2500ms 시나리오에서 timeout 경로를 확인했다. |
| CircuitBreaker sliding window size | 10 | 로컬 검증에서 실패율 변화를 빠르게 관찰하기 위한 작은 count window다. 운영 트래픽에서는 더 큰 window로 흔들림을 줄일 수 있다. |
| CircuitBreaker minimum calls | 5 | 1~2건 실패로 즉시 open되는 것을 막되, 반복 장애는 빠르게 감지한다. |
| CircuitBreaker failure threshold | 50% | 최근 호출의 절반 이상이 실패하면 PG 장애로 간주한다. 시뮬레이터의 거절/timeout 결과도 실패로 기록한다. |
| CircuitBreaker open duration | 10s | 장애 PG를 짧게 차단하고, 이후 half-open으로 회복 가능성을 확인한다. 로컬 검증을 위해 짧게 두었고 운영에서는 PG 복구 특성에 맞춰 늘릴 수 있다. |
| CircuitBreaker half-open permitted calls | 2 | 회복 확인 호출을 소량만 허용한다. 장애가 계속되면 다시 open되어 내부 자원 사용을 제한한다. |
| User RateLimit | 5 new idempotency keys / 1s / user | 같은 멱등키 재시도는 제한하지 않는다. 서로 다른 새 결제 시도만 제한해 다중 탭/자동화/클라이언트 버그가 서버 자원을 독점하지 못하게 한다. |

## 수동 개입 경계

- RECOVERY_NEEDED 반복 실패 체류 → ERROR 로그(requestId, transactionId, errorCode)로 식별
- 외부 결제 취소 반복 실패 → transactionId로 PG사 대사(reconciliation)

---

## 확정 사항 요약

- [x] **Fail-Closed 기본 정책**: Redis 장애 시 503 거절. 제한적 DB-only degraded mode는 부하테스트 근거가 있을 때만 운영 옵션
- [x] **Recovery Job**: 30s 주기 / 60s stuck 판정 / 분산 중복 실행은 constraint로 안전
- [x] **HikariCP**: connection-timeout 3초, pool-size 20
- [x] **Resilience4j**: PG 호출 Bulkhead/TimeLimiter/CircuitBreaker 적용. RateLimiter는 Redis 기반 사용자 요청 보호로 별도 적용
