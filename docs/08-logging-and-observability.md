# 08. 로깅과 관측 가능성

> 상태: **확정**

## 목적

장애가 났을 때 "이 요청이 어느 단계까지 갔고, 어디서 왜 멈췄는가"를
**로그 + booking_requests 상태 한 행**으로 답할 수 있어야 한다.

서버가 2대 이상이므로 **한 예약의 경로를 인스턴스를 넘나들며 재구성**할 수 있어야 한다.
→ correlation id(`traceId`)를 전 흐름에 관통시키는 것이 양보 불가의 핵심.

## correlation id (traceId)

- `traceId` = 멱등키(또는 요청 수신 시 생성한 requestId).
- 요청 수신 → 재고 선점 → 결제 각 단계 → 보상 각 단계 → 최종 상태까지 동일 값으로 관통.
- API 에러 응답 봉투의 `traceId`(→ 10)와 같은 값 → 클라이언트가 받은 traceId로 서버 로그를 바로 추적.
- 구현: MDC가 아닌 **명시적 컨텍스트 객체 전달**. step B(외부 호출) 등 비동기/스레드 경계가 있어
  ThreadLocal(MDC)은 누수·유실 위험이 있다.

## 구조화 로그 (JSON)

JSON 구조화 로그로 출력한다 (파싱·검색·집계 용이).

| 필드 | 값 출처 | 비고 |
|------|--------|------|
| traceId | 멱등키 / requestId | 전 구간 추적자, 인스턴스 넘나듦 |
| instanceId | 서버 인스턴스 식별자 | 어느 서버가 처리했는지 |
| userId | 요청 | |
| productId | 요청 | |
| paymentMethod | 요청 (조합 라벨, 예: `CREDIT_CARD+Y_POINT`) | |
| bookingRequestStatus | 상태 머신 현재 값 (→ 03) | |
| pgStatus | NONE / APPROVING / APPROVED / DECLINED / IN_DOUBT / CANCELED | step A~C 추적 |
| pgTxId | PG 승인 식별자 또는 - | in-doubt 정산·대사용 |
| redisOperationResult | RESERVED / SOLD_OUT / KEY_MISSING / RELEASED / RELEASE_FAILED / UNAVAILABLE / SKIPPED | |
| dbTransactionResult | COMMITTED / ROLLED_BACK / CONSTRAINT_VIOLATION / NONE | |
| errorCode | ErrorCode enum 또는 - | API 에러 코드와 동일 enum (→ 10) |
| elapsedMs | 흐름 시작부터 측정 | |

```json
{
  "event": "booking_flow",
  "traceId": "550e8400-...",
  "instanceId": "app-2",
  "userId": 1,
  "productId": 1,
  "paymentMethod": "CREDIT_CARD+Y_POINT",
  "bookingRequestStatus": "CONFIRMED",
  "pgStatus": "APPROVED",
  "pgTxId": "PG-xxxx",
  "redisOperationResult": "RESERVED",
  "dbTransactionResult": "COMMITTED",
  "errorCode": "-",
  "elapsedMs": 42
}
```

## 마스킹

- 카드번호: 끝 4자리만(`****-****-****-1234`). 전체는 절대 로그 금지.
- YPay 토큰: 로그 금지.
- 금액·포인트: 운영 추적에 필요하므로 남기되 PII 아님.

## 로깅 시점과 레벨

**라인마다가 아니라 결정 지점마다.** 피크에 로그가 폭증하지 않도록 전이 지점에서만 남긴다.

| 시점 | 레벨 | 내용 |
|------|------|------|
| 요청 수신 | INFO (또는 DEBUG) | traceId, userId, productId |
| 재고 선점 결과 | INFO | RESERVED / SOLD_OUT / UNAVAILABLE |
| 결제 시도·결과 | INFO | pgStatus + pgTxId |
| 보상 각 단계 | INFO | 포인트 환불 / 재고 복구 결과 |
| 흐름 종결 (성공/실패 공통) | INFO | 위 전체 필드 — **요청당 종결 1줄 보장** |
| APPROVING lease 만료 (in-doubt) | WARN | Recovery Job 정산 대상 |
| Redis 보상(INCR) 실패 | ERROR | "stock leak". under-sell 발생 지점 |
| COMPENSATING 진입/완료 | INFO | CAS 가드 통과 여부 |
| RECOVERY_NEEDED 진입 | ERROR | + pgTxId. 수동 개입 후보 |
| Fail-Closed 발동 (Redis 장애) | WARN | 장애 감지 시점 식별 |
| recovery job 처리 결과 | INFO/ERROR | 대상 건수, 수렴/실패 내역 |

- 중간 진행은 DB 상태가 증언하므로 과도한 단계별 INFO는 피한다.

## 상태 기반 관측

로그 외에 DB 자체가 관측 소스다.

| 질의 | 의미 |
|------|------|
| `status = 'RECOVERY_NEEDED'` 건수 | 보상 실패 잔존 — 0이 정상 |
| `status = 'APPROVED' AND updated_at < now - 60s` 건수 | 확정 직전 크래시 의심 — recovery 대상 |
| `status = 'APPROVING' AND lease_expires_at < now()` 건수 | in-doubt — 승인 식별자 없이 만료된 보상 대상 |
| `status = 'COMPENSATING'` 장기 체류 | 보상 중 크래시 — recovery 대상 |
| `available_quantity` vs Redis `stock:` | drift 감시 |

## 모니터링/알람 (운영 가정, 구현 범위 외)

- Fail-Closed WARN 발생률, RECOVERY_NEEDED > 0, in-doubt 잔존, drift 임계 초과 시 알람.
- 메트릭 시스템(Micrometer 등) 연동은 범위 외로 두되, 로그/상태 설계가 이를 가능하게 함을 명시.

---

## 확정 사항 요약

- [x] **traceId 관통**: 멱등키 = traceId = API 에러 봉투 traceId. 인스턴스 넘나들며 경로 재구성
- [x] **JSON 구조화 로그**: 결정 지점마다, 종결 시 요청당 1줄
- [x] **명시적 컨텍스트 전달**: MDC(ThreadLocal) 대신. 스레드 경계 누수 방지
- [x] **마스킹**: 카드 끝 4자리, 토큰 로그 금지
- [x] **상태 기반 관측**: in-doubt/COMPENSATING/RECOVERY_NEEDED를 DB 질의로 감시
