# Decisions

구현 중 핵심 의사결정만 정리합니다. README는 구조와 사용법을 설명하고, 이 문서는 선택지와 이유를 기록합니다.

## 1. Concurrency and Inventory

### D1. Redis admission을 DB write 앞에 둔다

| 항목 | 내용 |
|------|------|
| 문제 | 매진 이후 요청까지 모두 DB에 쓰면 피크 상황에서 DB가 병목이 된다. |
| 결정 | `StockGate`의 Redis Lua admission을 먼저 통과한 요청만 DB에 기록한다. |
| 근거 | Redis는 빠른 부하 차단, DB는 영속 정합성 검증 역할로 나눈다. |
| 포기 | JVM lock, 로컬 캐시, 무제한 DB fallback |

### D2. DB는 최종 정합성 방어선이다

| 항목 | 내용 |
|------|------|
| 문제 | Redis 값은 drift가 날 수 있다. |
| 결정 | DB에서 `WHERE available_quantity > 0` 조건부 UPDATE를 한 번 더 수행한다. |
| 근거 | Redis가 부풀려져도 DB reserve 단계에서 결제 전 거절할 수 있다. |

### D3. 재고는 available/reserved/sold로 나눈다

| 항목 | 내용 |
|------|------|
| 문제 | `total - sold` 모델은 결제 진행 중 재고를 표현하지 못한다. |
| 결정 | `available_quantity`, `reserved_quantity`, `sold_quantity`를 둔다. |
| 근거 | Redis sync가 진행 중 예약을 되살리는 문제를 피할 수 있다. |

## 2. Idempotency

### D4. 멱등키 범위는 userId + idempotencyKey다

| 항목 | 내용 |
|------|------|
| 문제 | 멱등키를 전역 unique로 두면 서로 다른 사용자의 키가 충돌할 수 있다. |
| 결정 | `UNIQUE(user_id, idempotency_key)`를 사용한다. |
| 근거 | 클라이언트 멱등키는 사용자 요청 범위에서 해석하는 것이 자연스럽다. |

### D5. 같은 키 다른 payload는 409로 거절한다

| 항목 | 내용 |
|------|------|
| 문제 | 같은 멱등키로 다른 상품/금액을 보내면 기존 응답 재생이 위험하다. |
| 결정 | canonical payload의 `request_hash`를 저장하고 다르면 409를 반환한다. |
| 근거 | 키 재사용 실수를 금전 사고로 연결하지 않는다. |

## 3. Transaction Boundaries

### D6. BookingService 전체 트랜잭션을 두지 않는다

| 항목 | 내용 |
|------|------|
| 문제 | 외부 PG 호출 중 DB 커넥션을 잡으면 커넥션 풀이 쉽게 고갈된다. |
| 결정 | `BookingService`는 비트랜잭션 오케스트레이터로 두고, 단계별 짧은 트랜잭션을 호출한다. |
| 근거 | 각 단계 상태를 durable하게 남기면서 외부 호출 중 커넥션 점유를 피한다. |

### D7. 확정은 단일 트랜잭션으로 묶는다

| 항목 | 내용 |
|------|------|
| 문제 | 예약/결제/재고 확정 중 일부만 저장되면 불일치가 생긴다. |
| 결정 | `BookingFinalizer`에서 `reserved -> sold`, booking insert, payment insert, `CONFIRMED` 저장을 한 트랜잭션으로 처리한다. |
| 근거 | 확정 단계 내부는 모두 성공하거나 모두 롤백되어야 한다. |

## 4. Payment and Compensation

### D8. 포인트를 먼저 차감하고 외부 결제를 나중에 승인한다

| 항목 | 내용 |
|------|------|
| 문제 | 외부 승인 후 자사 포인트 차감이 실패하면 외부 취소가 필요하다. |
| 결정 | 포인트 선차감 후 외부 결제를 승인한다. |
| 근거 | 포인트는 자사 DB라 환불이 쉽고, 외부 취소는 더 불확실하다. |

### D9. 보상은 effectively-once로 설계한다

| 항목 | 내용 |
|------|------|
| 문제 | 즉시 실패 경로와 Recovery가 동시에 보상할 수 있다. |
| 결정 | `PAYMENT_FAILED -> COMPENSATING` CAS, `point_history` unique, `stock_restore_status`를 사용한다. |
| 근거 | 외부 시스템과 Redis까지 포함한 정확한 exactly-once는 주장하지 않고, 최종 효과가 한 번만 반영되게 한다. |

### D10. Redis 재고 복구는 best-effort 1회만 한다

| 항목 | 내용 |
|------|------|
| 문제 | Redis `INCR` 성공 직후 앱이 죽으면 성공 여부가 불명확하다. |
| 결정 | Redis 복구는 한 번만 시도하고 실패/불명확하면 `NEEDS_SYNC`로 남긴다. |
| 근거 | 중복 `INCR`은 실제보다 많은 재고를 만들어 초과 판매 방향으로 위험하다. |

## 5. Failure Recovery

### D11. 비종결 상태는 Recovery Job 대상이다

| 항목 | 내용 |
|------|------|
| 문제 | 앱은 어느 단계에서든 중단될 수 있다. |
| 결정 | `STOCK_RESERVED`, `APPROVING`, `APPROVED`, `COMPENSATING`, `RECOVERY_NEEDED`를 스캔한다. |
| 근거 | 중간 상태를 DB에 남기면 재실행이 곧 복구가 된다. |

### D12. APPROVING lease 만료는 보상 수렴한다

| 항목 | 내용 |
|------|------|
| 문제 | 현재 PG 시뮬레이터에는 inquiry API가 없다. |
| 결정 | `APPROVING` 상태에서 lease가 만료되고 `pgTxId`가 없으면 보상 경로로 수렴한다. |
| 근거 | 승인 식별자가 없는 상태에서 확정을 시도하지 않는다. |
| 추후 개선 | PG inquiry 계약을 추가하면 승인/미승인을 조회해 분기할 수 있다. |

## 6. Availability and Observability

### D13. Redis admission 장애는 Fail-Closed다

| 항목 | 내용 |
|------|------|
| 문제 | Redis 없이 선착순 admission 순서를 보장하기 어렵다. |
| 결정 | Redis 연결 실패, timeout, stock key missing은 503으로 거절한다. |
| 근거 | 무제한 DB fallback은 피크 요청을 DB로 직격시킨다. |

### D14. PG 호출은 Bulkhead, TimeLimiter, CircuitBreaker로 보호한다

| 항목 | 내용 |
|------|------|
| 문제 | 느린 PG 호출이 예약 API 스레드를 고갈시킬 수 있다. |
| 결정 | `ProtectedExternalPaymentGateway`에서 Resilience4j Bulkhead, TimeLimiter, CircuitBreaker를 적용한다. |
| 근거 | 느린 호출은 timeout, 과도한 병렬 호출은 bulkhead, 반복 실패는 circuit open으로 차단한다. |
| 설정 | bulkhead 20, timeout 2s, circuit window 10, minimum calls 5, failure threshold 50%, open duration 10s |

### D15. 로그는 JSON, 추적 키는 명시적으로 남긴다

| 항목 | 내용 |
|------|------|
| 문제 | 장애 원인을 요청 단위로 추적해야 한다. |
| 결정 | logstash encoder로 JSON 로그를 출력하고 API 에러 응답에 `traceId`를 포함한다. |
| 근거 | `booking_requests` 상태와 로그를 함께 보면 실패 지점을 좁힐 수 있다. |

### D16. 단일 애플리케이션 안에서 헥사고날 경계를 둔다

| 항목 | 내용 |
|------|------|
| 문제 | 멀티모듈이나 MSA로 쪼개기에는 규모가 작지만, API/Redis/PG 구현이 유스케이스에 섞이면 변경 비용이 커진다. |
| 결정 | modular monolith로 유지하되 `api -> application -> domain` 방향을 기본으로 하고, Redis/PG/scheduler는 application port를 구현하는 infra adapter로 둔다. |
| 근거 | 배포 단위는 단순하게 유지하면서도 HTTP DTO, Redis Lua, Resilience4j, PG simulator 교체 영향을 application 경계 밖으로 제한할 수 있다. |
| 검증 | ArchUnit으로 `domain -> api/application/infra`, `application -> api/infra` 의존을 금지한다. |
