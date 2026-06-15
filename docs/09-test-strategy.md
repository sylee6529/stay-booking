# 09. 테스트 전략

> 상태: **확정**

## 원칙

1. **실제 저장소로 검증한다.** 이 시스템의 핵심 보장(unique constraint, 조건부 UPDATE의 원자성, Lua 스크립트)은
   H2/embedded 호환 모드로는 검증되지 않는다. → Testcontainers로 MySQL 8 + Redis 7을 띄운다.
2. **랜덤성을 배제한다.** 결제 시뮬레이터는 입력값으로 성패가 결정된다 (→ 06). 동시성 테스트도
   "성공 수 ≤ 재고"라는 결정적 불변식을 단언한다.
3. **정합성 증명에 집중한다.** 커버리지 욕심 대신, 시스템의 중심 주장(초과판매 0)을 코드로 입증하는
   테스트에 자원을 몰아준다. **이것이 제출물 전체에서 ROI가 가장 높은 산출물이다.**

## 테스트 환경 구성

- `IntegrationTestSupport` 추상 클래스: MySQL/Redis 컨테이너를 static으로 1회 기동, 전 테스트 공유 (속도).
- 각 테스트는 자기 상품/사용자 데이터를 직접 생성하고 `StockSyncService`로 Redis 키를 초기화.
- recovery job 스케줄러는 테스트에서 비활성화(`app.recovery.enabled=false`)하고, 복구 테스트는 메서드 직접 호출.
- Redis 장애 테스트는 컨테이너를 멈추는 대신 **연결 불가능한 포트를 가리키는 별도 컨텍스트**로 구성
  (공유 컨테이너를 멈추면 다른 테스트가 오염되고, 닫힌 포트는 즉시 connection refused라 타임아웃 대기도 없다).

## 필수 시나리오

| # | 시나리오 | 검증 불변식 | 방법 |
|---|----------|------------|------|
| **T1** | **재고 10, 동시 요청 1000** | **확정 예약 == 10, `available=0`, `reserved=0`, `sold=10`, Redis 잔량 == 0, 결제 == 10건** | **ExecutorService 1000 스레드, 서로 다른 키/사용자. 이 시스템의 중심 증명** |
| T2 | 동일 idempotencyKey 동시/반복 요청 | booking_requests 1행, 예약 ≤ 1, 결제 ≤ 1, 같은 키 = 같은 응답 | 같은 키 20 스레드 동시 + 순차 재요청 |
| T2-1 | 같은 idempotencyKey 다른 payload | 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD | productId/pointAmount 변경 재요청 |
| T3 | 결제 실패 시 보상 | Redis 잔량 원복, 예약 0건, 포인트 환불됨, 상태 FAILED | 거절 카드번호(`...0000`) + 포인트 병용 → 포인트 환불 + 재고 복구 동시 검증 |
| T4 | Card + Point 성공 | 예약 1, 결제 1행(분배 금액 일치), 포인트 잔액 차감 | |
| T5 | Card + YPay 혼용 | 400 거절, 상태 기록 없음, 재고/포인트 불변 | |
| T6 | 매진 fast-fail | 409 SOLD_OUT, 예약/결제 0건 | 재고 0 상태에서 요청 |
| T7 | Redis 장애 (Fail-Closed) | 503 STOCK_GATE_UNAVAILABLE, 예약/결제 0건, 상태 REJECTED | 닫힌 포트 컨텍스트 |
| T8 | DB unique 중복 방어 | 같은 idempotency_key 직접 INSERT 2회 → constraint violation | 리포지토리 레벨 |
| T9 | 확정 직전 크래시 복구 | APPROVED로 멈춘 요청 → recovery 실행 → CONFIRMED + 예약 생성 | 상태 직접 조작 후 RecoveryService 호출 |
| T10 | in-doubt 정산 (lease 만료) | APPROVING + lease 만료 → PG inquiry → SUCCEEDED 정착 또는 보상 | 상태/lease 직접 조작 후 recovery 호출 |
| T10-1 | PG 호출 전 선점 만료 | STOCK_RESERVED + reservation 만료 → reserved release 또는 NEEDS_SYNC | 상태/만료 직접 조작 후 recovery 호출 |
| T11 | 포인트 부족 | INSUFFICIENT_POINT 거절, Redis 재고 복구 | |
| T12 | 확정 단계 DB 매진(SOLD_OUT_DB) | 결제 취소됨, REJECTED, oversell 없음 | Redis 재고를 DB보다 부풀려 drift 재현 |
| T13 | 보상 중복 방지 | 즉시 경로 + recovery 동시 트리거 → 포인트 1회 환불, Redis INCR 중복 없음, 필요 시 NEEDS_SYNC | CAS 가드 + stock_restore_status 검증 |

**T1, T2, T3이 핵심 3종**: 초과판매 0 / 멱등성 / 보상 정합성. 시간 제약 시 이 셋을 최우선 확보.

## 단위 테스트

- 조합 검증 규칙(06)과 금액 분배 로직: 컨테이너 없이 순수 단위 테스트 (빠른 피드백).
- Orchestrator 부분 실패 보상(포인트 환불 호출 여부): 게이트웨이 모킹으로 단위 검증.
- PG timeout/bulkhead fast-fail: 느린 게이트웨이 모킹으로 격리 동작 검증.

## 다루지 않는 것

- 카오스 테스트(네트워크 분단 등): Redis 장애는 연결 거부 수준만 재현.

## 부하 테스트와 병목 관찰

부하 테스트는 운영 TPS 보장을 위한 성능 인증이 아니다.
로컬/과제 환경에서 설계상 병목이 DB 재고 row, Redis 게이트, PG 지연, RateLimiter 중 어디에 있는지
관찰하고 README/DECISIONS.md에 근거로 남기기 위한 자료다.

- 도구: k6 스크립트(`load-test/booking.js`)를 사용한다. 실행 환경에는 k6 설치가 필요하다.
- 관찰 지표:
  - 확정 예약 수 = 재고 수량, `available/reserved/sold` 불변식 유지
  - 200/409/429/503 응답 비율
  - p95/p99 latency
  - PG Bulkhead/TimeLimiter/CircuitBreaker rejection 수
  - Hikari active/pending connection
- 해석 경계: 로컬 결과를 프로덕션 처리량 보장으로 주장하지 않는다. 병목 위치와 보호 장치 작동 여부만 설명한다.

제출 시 README 또는 `docs/performance-report.md`에 실제 결과표를 남긴다.

| 항목 | 기록 내용 |
|------|----------|
| 환경 | CPU/RAM, app instance 수, MySQL/Redis 실행 방식 |
| 설정 | Hikari pool size, Redis timeout, PG mock latency, Resilience4j 제한 |
| 시나리오 | baseline, redis-admission, redis-down, duplicate-click, payment-failure |
| 결과 | total requests, confirmed count, oversell count, p95/p99, 200/409/429/503 비율 |
| 판단 | Redis admission 위치, Fail-Closed/degraded mode, pool size 선택 근거 |

---

## 확정 사항 요약

- [x] **Testcontainers(MySQL 8 + Redis 7)**: H2 배제. 실 저장소로 핵심 보장 검증
- [x] **T1 = 재고 10 + 1000 동시 요청 → 정확히 10건**: 시스템 중심 주장의 코드 증명
- [x] **핵심 3종 우선**: T1(초과판매 0) / T2(멱등성) / T3(보상 정합성)
- [x] **in-doubt/lease 복구(T10), STOCK_RESERVED 만료(T10-1), 보상 중복 방지(T13)** 시나리오 포함
- [x] **부하 테스트**: k6로 병목 관찰. 프로덕션 성능 보장이 아니라 설계 근거 자료로 사용
