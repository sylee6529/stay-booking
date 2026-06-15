# 검토 체크리스트

설계 문서 전체에 대한 결정 현황. 확정된 항목과 남은 검토 항목을 구분한다.

## 문서 현황

| 문서 | 내용 | 상태 |
|------|------|------|
| [00-problem-definition](00-problem-definition.md) | 문제 해석, TPS 계산, 공정성 해석, 범위 | **확정** |
| [01-requirements-and-assumptions](01-requirements-and-assumptions.md) | 필수 요구 / 가정 | **확정** |
| [02-architecture-overview](02-architecture-overview.md) | 구조, 레이어, 기술 선택, 포기한 것 | **확정** |
| [03-booking-flow](03-booking-flow.md) | 예약 흐름, Orchestrator 구조, APPROVING/APPROVED, 상태 머신 | **확정** |
| [04-concurrency-and-inventory](04-concurrency-and-inventory.md) | 재고 동시성, Lua, DB 원자 UPDATE, drift | **확정** |
| [05-idempotency](05-idempotency.md) | 멱등성, 상태 3분류, lease, 실패 키 정책 | **확정** |
| [06-payment-design](06-payment-design.md) | Strategy, Orchestrator, Saga 보상, effectively-once | **확정** |
| [07-failure-recovery](07-failure-recovery.md) | 장애 매트릭스, Fail-Closed 근거, Saga 보상 설계, recovery job | **확정** |
| [08-logging-and-observability](08-logging-and-observability.md) | traceId 관통, JSON 구조화 로그, 상태 기반 관측 | **확정** |
| [09-test-strategy](09-test-strategy.md) | Testcontainers, T1=1000동시요청, 핵심 3종 | **확정** |
| [10-api-spec](10-api-spec.md) | API 계약, 에러 코드, 멱등 재생 응답 구분 | **확정** |
| [11-schema](11-schema.md) | 단순 스키마, schema.sql, 보상 상태 컬럼 | **확정** |

## 확정된 결정 (구현 시 변경 없음)

### 핵심 구조
- [x] **"Redis = 990개를 빠르게 거절하는 부하 차단기, DB = 정합성 최종 심판"** — 모든 설계의 전제
- [x] **이중 방어**: Redis admission + DB 조건부 UPDATE (`WHERE available_quantity > 0`)
- [x] **DB 원자 UPDATE**: read-modify-write 금지. 단일 조건부 UPDATE 문장만 허용
- [x] **분산 환경**: 동일 앱 Scale-out (MSA 아님). JVM 로컬 락 무의미

### Redis 장애 정책
- [x] **Fail-Closed 기본 정책** — 무제한 DB Fallback(30만 요청 직격), 전역 Rate Limit(공정성 훼손), 로컬 캐시(분산 정합성 불가), 메시지 큐(동기 응답 불가) 모두 탈락
- [x] **제한적 DB-only degraded mode** — 부하테스트로 검증된 동시성 한도 안에서만 운영 옵션
- [x] **공정성 해석**: 순수 선착순 + 시스템 개입 없음. Redis 단일 스레드 원자 처리가 서버 내부 순서 보장
- [x] **제한적 Rate Limit**: 공정성/정합성 장치가 아니라 동일 사용자 과도 요청 방지용 자원 보호 장치

### 재고
- [x] `stock:` 키 TTL 없음
- [x] SOLD_OUT_DB 시 Redis 미보상 (DB가 소진 선언 = Redis 값이 틀린 것)
- [x] DB → Redis 단방향 sync. `StockSyncService`가 DB `available_quantity` 기준 덮어쓰기

### 고가용성 설정
- [x] HikariCP: `connection-timeout=3000`, `maximum-pool-size=20`
- [x] Redis timeout: `2s`
- [x] **Resilience4j**: PG 호출 Bulkhead + TimeLimiter + CircuitBreaker. RateLimiter는 제한적으로 적용

## 남은 검토 항목

### 확정된 결제/보상 설계 추가 항목

- [x] **Saga 패턴 보상**: COMPENSATING 중간 상태와 CAS 가드로 한 경로만 보상 진입
- [x] **CAS 가드**: `UPDATE ... WHERE status='PAYMENT_FAILED'` → 0 rows = 스킵
- [x] **보상 단계 멱등**: point_history UNIQUE(order_id, type), Redis 복구는 stock_restore_status로 추적, 종결 마지막
- [x] **크래시 안전 방향**: Redis INCR 중복 재시도 금지. 불명확하면 NEEDS_SYNC 후 DB 기준 stock sync
- [x] **stock-sync 구조적 필수**: 보상 INCR 누락 시 Redis 영구 under-count → sync 없으면 복구 불가
- [x] **부분 실패 (포인트 차감 후 카드 거절)**: Orchestrator가 포인트 환불 후 Saga 보상 흐름과 동일하게 처리

### 추가 확정 항목

- [x] **(03) Orchestrator 비트랜잭션**: REQUIRES_NEW 없이 독립 tx 순차 실행
- [x] **(03) Redis admission 선행**: 매진 초과 요청은 DB INSERT 전에 차단
- [x] **(03) STOCK_RESERVED 만료 복구**: PG 호출 전 선점 후 크래시도 recovery 대상
- [x] **(03) APPROVING + lease**: PG 호출 전 마커. Recovery Job in-doubt 판정 근거
- [x] **(03) APPROVED + pg_tx_id**: PG 승인 후 즉시 커밋. Recovery Job이 확정만 재시도
- [x] **(05) 멱등키 상태 3분류**: IN_PROGRESS / SUCCEEDED / BUSINESS_FAILED. in-doubt 굳히지 않음
- [x] **(05) request_hash**: 같은 key로 다른 payload를 보내면 409
- [x] **(05) 실패 키 1회용**: 같은 키 = 항상 같은 응답. 재시도는 새 키 (Stripe 방식)
- [x] **(05) lease + Recovery Job**: 고아 IN_PROGRESS 유한 시간 안에 정착. 클라이언트 계약 닫힘
- [x] **(10) 완료=200 재생, 진행중=409+status, 실패=원 응답 재생**: 구분 확정
- [x] **(10) in-doubt API 계약**: 외부 API는 REQUEST_IN_PROGRESS로 통일, 내부 로그/상태에서만 IN_DOUBT 구분
- [x] **(10) traceId**: 멱등키를 에러 봉투에 포함. 전 흐름 로그 연결
- [x] **(11a) 단순 스키마**: `promotion_products` 하나에 상품/재고를 둔다. 과제 핵심 설명을 우선
- [x] **(11b) schema.sql 사용**: Flyway는 운영 확장안으로만 남김

### 추가 확정 항목 (08/09/11)

- [x] **(08) traceId 관통**: 멱등키 = traceId = API 에러 봉투. JSON 구조화 로그, 결정 지점마다
- [x] **(08) 명시적 컨텍스트 전달**: MDC 대신. 스레드 경계 누수 방지
- [x] **(09) T1 = 재고 10 + 1000 동시 요청 → 정확히 10건**: 시스템 중심 증명. 핵심 3종(T1/T2/T3)
- [x] **(09) in-doubt(T10)·STOCK_RESERVED 만료(T10-1)·보상 중복 방지(T13)** 시나리오 포함
- [x] **(11) 단순 스키마 + schema.sql**: 초기 과제 제출물에 맞게 구현/설명 비용 축소
- [x] **(11) 재고 수량 컬럼**: available/reserved/sold로 Redis sync와 진행 중 예약을 명확히 표현
- [x] **(11) 보상 상태 컬럼**: points_refunded, stock_restore_status, point_history UNIQUE + pg_status/reservation/lease 추적

### 설계 미결 사항: **없음** — 전 문서 확정 완료. 구현 단계로 진행 가능.

## 최종 산출물 계획 (구현 완료 후)

- **DECISIONS.md** — 확정된 결정들을 "상황 / 고민한 것 / 대안 / 결정 / 근거 / 포기한 것" 형식으로 기록
- **README.md** — 실행 방법, API, 아키텍처, 시퀀스 다이어그램, ERD/DDL, 테스트 실행 방법
