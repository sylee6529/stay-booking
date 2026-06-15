# 02. 아키텍처 개요

> 상태: **확정**

## 설계의 제1원칙

> **Redis는 "990개를 빠르게 거절하는 부하 차단기", DB는 정합성의 최종 심판.**

Redis는 어차피 실패할 요청을 밀리초 안에 돌려보내는 성능 장치다.
Redis가 틀려도(장애, drift) DB의 unique constraint와 조건부 UPDATE가 초과 판매·중복 결제를
물리적으로 차단한다. 반대로 DB가 멀쩡해도 Redis 없이는 피크(최대 30만 요청)를 견디지 못한다.
두 저장소의 책임을 섞지 않는 것이 이 아키텍처의 핵심이다.

## 시스템 구성

```
                       ┌─────────────────────────────┐
   Client ──────────▶  │  App Server × N (stateless) │
   (00시 폭주)         └──────┬───────────────┬──────┘
                              │               │
                  1차 admission │               │  최종 정합성
                              ▼               ▼
                       ┌────────────┐  ┌──────────────────┐
                       │   Redis    │  │      MySQL       │
                       │ ---------- │  │ ---------------- │
                       │ 재고 admission│  │ 재고 예약/확정      │
                       │ (Lua 원자)  │  │ (조건부 UPDATE)   │
                       │ 멱등성 캐시 │  │ 멱등성 진실        │
                       │ 사용자 요청 제한│ │ (unique key)      │
                       │            │  │ 예약/결제/상태머신  │
                       └────────────┘  └──────────────────┘
                         탈락 트래픽 흡수    admission 통과 요청만 기록
```

- 서버는 무상태(stateless). 모든 경합 제어는 Redis(원자 연산)와 MySQL(제약 조건)에서만 일어난다.
- 00시 트래픽 중 "어차피 실패할 요청"(재고 초과분)은 Redis admission에서 밀리초로 탈락시켜 DB write에 도달하지 않게 한다.

## 코드 레이어 구조

```
com.example.staybooking
├── adapter/
│   ├── in/
│   │   ├── web/             # HTTP adapter. Controller, HTTP DTO, 예외 → HTTP 매핑
│   │   └── scheduler/       # Spring Scheduled 진입점
│   └── out/
│       ├── redis/           # Redis admission adapter
│       └── payment/         # PG simulator + Resilience4j adapter
├── application/    # 유스케이스 오케스트레이션
│   ├── BookingService        # 예약 흐름 전체 (트랜잭션 밖에서 단계 조율)
│   ├── BookingFinalizer      # 확정 트랜잭션(TX1) 단독 담당
│   ├── BookingRequestStore   # 상태 머신 기록 (REQUIRES_NEW 즉시 커밋)
│   ├── CheckoutService
│   ├── RecoveryService       # 미완결 요청 스캔/수렴
│   ├── StockSyncService      # DB → Redis 재고 재계산 (단방향)
│   ├── booking/, checkout/   # application command/result
│   ├── payment/              # PaymentOrchestrator + PaymentProcessor 전략
│   └── port/out/             # Redis admission, 외부 PG port
├── domain/         # 엔티티, 리포지토리, 상태 enum. adapter 비의존
└── config/         # Spring 설정, typed properties
```

- **Inbound adapter ↔ application 분리**: Controller가 HTTP DTO를 application command/result로 변환한다. application은 `adapter.in.web`을 의존하지 않는다.
- **application ↔ outbound adapter 분리**: `BookingService`는 `StockGatePort`와 `PaymentOrchestrator`만 알고,
  Lua 스크립트·Lettuce·시뮬레이터·Resilience4j 구현은 `adapter.out`에 격리한다.
- **결제 수단 비의존**: BookingService → PaymentOrchestrator → PaymentProcessor(전략) 방향으로만 의존.
- **경계 회귀 방지**: ArchUnit 테스트가 `domain -> adapter/application/config`, `application -> adapter`, `adapter.in -> adapter.out` 의존을 금지한다.

## 기술 선택

| 항목 | 선택 | 핵심 근거 (상세는 각 문서) |
|------|------|---------------------------|
| 분산 환경 | 동일 앱 서버 Scale-out (MSA 아님) | "2대 이상 앱 서버" 요구는 수평 확장으로 해석. MSA는 분산 트랜잭션 문제를 추가하고 현재 범위 대비 과설계 |
| 데이터 접근 | JPA + 동시성 민감 갱신은 조건부 벌크 UPDATE | 일반 영속화는 JPA가 생산적. 재고·포인트는 read-modify-write가 lost update에 취약 → `UPDATE ... WHERE 조건` 원자 갱신 (→ 04) |
| 재고 admission | Redis Lua 스크립트 | GET+DECR 분리 시 비원자. Lua는 admission 중복 확인과 재고 차감을 한 동작으로 처리 (→ 04) |
| 멱등성 | Redis admission/cache + DB unique | 진실은 DB(`UNIQUE(user_id, idempotency_key)`), Redis는 피크 중복 폭주와 매진 초과 요청 흡수 (→ 05) |
| Redis 장애 | **Fail-Closed** | 로컬 캐시는 분산 환경 정합성 불가. 전역 Rate Limit은 공정성 훼손. DB Fallback은 30만 요청 직격 위험. 세 가지 모두 불가 → 명시적 서비스 중단 (→ 07) |
| 고가용성 보호 | HikariCP 타임아웃 단축 + Resilience4j Bulkhead/TimeLimiter/CircuitBreaker | DB 커넥션 대기, PG 지연, 반복 PG 실패가 서버 스레드를 오래 점유하지 않게 제한 (→ 07) |
| 요청 보호 | Redis fixed-window Rate Limit | 당첨 순서/정합성 장치가 아니라 동일 사용자 과도 요청을 막는 자원 보호 장치 (→ 07, 10) |
| 복구 | 상태 머신 + 보상 + recovery job | 외부 효과(결제)는 롤백 불가 → 보상 + 상태 영속화로 수렴 (→ 07) |

## 포기한 것과 이유

| 포기한 것 | 이유 |
|-----------|------|
| 메시지 큐 기반 대기열 | 동기 응답(`POST /booking` = 즉각 성공/실패) 요구와 충돌. 재고 10개를 위해 30만 메시지 인프라 운영은 비율 맞지 않음. 큐 도달 순서도 네트워크 속도에 의존 → 공정성 개선 없음 |
| 무제한 DB Fallback (Redis 장애 시) | 30만 요청이 DB 직격. 로컬 캐시는 분산 환경에서 서버 간 상태 불일치. 전역 Rate Limit은 선착순 공정성 훼손. 제한적 DB-only degraded mode는 부하테스트 근거가 있을 때만 운영 옵션 |
| 분산 트랜잭션(2PC/Saga) | 외부 결제는 XA 참여 불가. 보상 + 상태 머신으로 충분 |
| CQRS/이벤트 소싱 | 읽기 부하 분리가 문제의 본질이 아님 |
