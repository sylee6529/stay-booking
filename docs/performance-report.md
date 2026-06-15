# Performance Report

## 1. 검증 환경

| 항목 | 값 |
|------|----|
| 실행 시각 | 2026-06-15 |
| 애플리케이션 | Spring Boot local profile, port 8080 |
| DB/Redis | `docker compose` MySQL 8.0, Redis 7 |
| 부하 도구 | `load-test/booking.js` k6 시나리오 |

부하 수치는 로컬 단일 인스턴스에서 같은 요청 패턴으로 수집했다. 제출 기준 부하 스크립트는 `load-test/booking.js`다.

## 2. HTTP 부하테스트 시나리오

기본 실행 형식:

```bash
k6 run -e BASE_URL=http://localhost:8080 -e PRODUCT_ID=1 load-test/booking.js
```

시나리오별로 DB 데이터를 초기화하고 `/internal/products/1/stock-sync`로 Redis 재고를 동기화한 뒤 실행했다.

| 시나리오 | 설정 | 요청/동시성 | 재고 | 응답 분포 | 처리량 | p95 / p99 | 최종 DB/Redis |
|----------|------|-------------|------|-----------|--------|-----------|--------------|
| baseline | PG delay 0, 정상 카드 | 1000 / 100 | 10 | 200=10, 409=990 | 602.30 req/s | 440ms / 563ms | sold=10, reserved=0, Redis=0 |
| PG decline | PG delay 0, 거절 카드(`...0000`) | 100 / 20 | 100 | 422=100 | 97.90 req/s | 281ms / 295ms | sold=0, reserved=0, Redis=100 |
| PG timeout/circuit | PG delay 2500ms, TimeLimiter 2s | 50 / 20 | 50 | 422=50 | 18.91 req/s | 2435ms / 2445ms | sold=0, reserved=0, Redis=50 |

해석:

- baseline은 Redis admission + DB 조건부 UPDATE가 oversell 없이 수렴함을 확인한다.
- PG decline은 외부 결제 거절이 보상 경로로 들어가 재고를 원복함을 확인한다.
- PG timeout/circuit은 일부 요청이 TimeLimiter에 걸리고 이후 CircuitBreaker가 열리면서 후속 요청이 빠르게 실패한다. 그래서 p95/p99는 timeout 근처이고, 중간값은 circuit-open fast-fail 영향으로 낮아진다.
- 모든 시나리오에서 reserved 재고가 남지 않았다.
- 이 수치는 로컬 단일 인스턴스 측정이며 운영 처리량 보장이 아니다.

## 3. Baseline 최종 DB/Redis 검증

baseline 부하테스트 직후 직접 조회했다.

| 항목 | 값 |
|------|----|
| DB total_quantity | 10 |
| DB available_quantity | 0 |
| DB reserved_quantity | 0 |
| DB sold_quantity | 10 |
| bookings count | 10 |
| payments count | 10 |
| booking_requests CONFIRMED | 10 |
| Redis `stock:1` | 0 |
| oversell | 0 |

## 4. 장애 시나리오 최종 상태

PG decline:

| 항목 | 값 |
|------|----|
| DB available_quantity | 100 |
| DB reserved_quantity | 0 |
| DB sold_quantity | 0 |
| bookings count | 0 |
| payments count | 0 |
| booking_requests FAILED | 100 |
| Redis `stock:1` | 100 |

PG timeout/circuit:

| 항목 | 값 |
|------|----|
| DB available_quantity | 50 |
| DB reserved_quantity | 0 |
| DB sold_quantity | 0 |
| bookings count | 0 |
| payments count | 0 |
| booking_requests FAILED | 50 |
| Redis `stock:1` | 50 |

## 5. 설정값 근거

| 설정 | 값 | 이유 |
|------|----|------|
| Hikari `maximum-pool-size` | 20 per app instance | Hikari의 실제 DB 커넥션 상한이다. 설계 전제인 앱 서버 2대에서는 최대 40개 DB 커넥션을 열 수 있다. 이번 실측은 로컬 단일 인스턴스 기준이라 최대 20개만 검증했다. |
| Hikari `connection-timeout` | 3s | Hikari에서 커넥션을 기다리는 최대 시간이다. 기본 30s보다 짧게 두어 pool 포화 시 요청을 오래 붙잡지 않게 했다. 이번 부하에서는 timeout이 관찰되지 않았고, 포화 실패 실험은 별도 시나리오로 남긴다. |
| Redis timeout | 2s | Redis admission은 선착순 게이트다. 길게 대기하지 않고 503 Fail-Closed로 전환한다. |
| admission TTL | 5s | 동일 사용자/키의 중복 admission을 짧게 막는다. orphan admission은 under-sell 방향이라 초과 판매보다 안전하다. |
| PG Bulkhead max concurrent calls | 20 | DB pool과 같은 크기로 외부 호출 병렬도를 제한한다. PG 지연이 예약 API 전체 스레드 고갈로 번지는 것을 막는다. |
| PG TimeLimiter timeout | 2s | PG가 느릴 때 빠르게 결제 실패/보상 경로로 전환한다. |
| CircuitBreaker sliding window | 10 | 로컬 테스트에서 빠르게 실패율을 감지할 수 있는 작은 count window다. |
| CircuitBreaker minimum calls | 5 | 매우 적은 표본으로 열리는 것을 막되, 장애 반복 시 빠르게 open되게 한다. |
| CircuitBreaker failure threshold | 50% | 최근 호출 절반 이상이 실패하면 외부 PG 장애로 본다. |
| CircuitBreaker open duration | 10s | 장애 PG를 잠시 차단해 내부 자원을 보호하고, 이후 half-open으로 회복 가능성을 확인한다. |

### Hikari 해석 범위

설계 전제는 앱 서버 2대 이상이다. 따라서 `maximum-pool-size=20`은 전체 시스템 값이 아니라 **인스턴스당 값**이다. 운영 가정에서 서버 2대가 동시에 뜨면 DB는 최대 40개 커넥션을 허용해야 한다.

이번 측정은 `maximum-pool-size=20`, `connection-timeout=3s` 설정에서 **로컬 단일 인스턴스**가 1000 요청/동시성 100을 초과 판매 없이 처리했음을 보여준다. 이 값이 2대 구성에서 최적이라는 증거는 아니다.

추가 튜닝이 필요하면 같은 시나리오를 다음처럼 나눠 비교한다.

| 비교 축 | 목적 |
|---------|------|
| pool size 10 / 20 / 40 | 커넥션 수 증가가 처리량 또는 tail latency를 개선하는지 확인 |
| app instance 1 / 2 | 서버 2대에서 총 DB 커넥션 40개, Redis admission 공유, Recovery 중복 실행이 의도대로 동작하는지 확인 |
| concurrency 50 / 100 / 200 | pool 대기와 Redis admission 병목이 언제 나타나는지 확인 |
| PG delay 100ms / 500ms / 2s | Bulkhead, TimeLimiter, CircuitBreaker가 API 지연을 제한하는지 확인 |
| Redis down | Fail-Closed 응답 시간이 충분히 짧은지 확인 |

## 6. 자동 테스트 결과

실행 명령:

```bash
./gradlew test --rerun-tasks
```

결과:

| 항목 | 값 |
|------|----|
| tests | 43 |
| failures | 0 |
| errors | 0 |

주요 검증:

| 시나리오 | 검증 |
|----------|------|
| 재고 10, 동시 예약 1000 | 확정 10, oversell 0 |
| Redis down | 503 `STOCK_GATE_UNAVAILABLE`, 예약/결제 없음 |
| 카드 거절 + 포인트 | 포인트 환불, reserved release, Redis release, FAILED |
| `APPROVED` stuck | 확정 트랜잭션 재시도, CONFIRMED |
| `APPROVING` lease 만료 | 보상 경로, FAILED |
| 보상 동시 진입 | Redis 재고 1회만 복구 |
| Redis drift | DB reserve 단계에서 결제 전 SOLD_OUT |
| CircuitBreaker | 반복 PG 실패 후 open, 후속 호출 `PG_CIRCUIT_OPEN` |
