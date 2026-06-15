-- 재고의 진실: available_quantity / reserved_quantity / sold_quantity (DECISIONS.md D2, D7)
CREATE TABLE IF NOT EXISTS promotion_products
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    price              BIGINT       NOT NULL,
    total_quantity     INT          NOT NULL,
    available_quantity INT          NOT NULL,
    reserved_quantity  INT          NOT NULL DEFAULT 0,
    sold_quantity      INT          NOT NULL DEFAULT 0,
    checkin_date       DATE         NOT NULL,
    checkout_date      DATE         NOT NULL,
    open_at            DATETIME(6)  NOT NULL,
    CONSTRAINT chk_stock_non_negative CHECK (
        available_quantity >= 0 AND reserved_quantity >= 0 AND sold_quantity >= 0
    ),
    CONSTRAINT chk_stock_total CHECK (
        available_quantity + reserved_quantity + sold_quantity = total_quantity
    )
);

CREATE TABLE IF NOT EXISTS user_points
(
    user_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- 멱등성의 진실: UNIQUE(user_id, idempotency_key) (DECISIONS.md D3)
-- reservation_expires_at / pg_status / pg_tx_id / lease_expires_at 은 장애 복구의 근거 (DECISIONS.md D6)
CREATE TABLE IF NOT EXISTS booking_requests
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key      VARCHAR(64) NOT NULL,
    request_hash         CHAR(64)    NOT NULL,
    user_id              BIGINT      NOT NULL,
    product_id           BIGINT      NOT NULL,
    payment_methods      VARCHAR(40) NOT NULL,
    amount               BIGINT      NOT NULL,
    point_amount         BIGINT      NOT NULL DEFAULT 0,
    status               VARCHAR(20) NOT NULL,
    pg_status            VARCHAR(20) NOT NULL DEFAULT 'NONE',
    pg_tx_id             VARCHAR(64),
    reservation_expires_at DATETIME(6),
    lease_expires_at     DATETIME(6),
    points_refunded      BOOLEAN     NOT NULL DEFAULT FALSE,
    stock_restore_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
    failure_reason       VARCHAR(200),
    response_code        INT,
    response_body        JSON,
    completed_at         DATETIME(6),
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    CONSTRAINT uk_booking_requests_user_idempotency UNIQUE (user_id, idempotency_key),
    INDEX idx_booking_requests_status_updated (status, updated_at),
    INDEX idx_booking_requests_status_reservation (status, reservation_expires_at),
    INDEX idx_booking_requests_status_lease (status, lease_expires_at),
    INDEX idx_booking_requests_stock_restore (stock_restore_status)
);

CREATE TABLE IF NOT EXISTS bookings
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_request_id BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,
    product_id         BIGINT      NOT NULL,
    checkin_date       DATE        NOT NULL,
    checkout_date      DATE        NOT NULL,
    status             VARCHAR(20) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_bookings_booking_request UNIQUE (booking_request_id)
);

CREATE TABLE IF NOT EXISTS payments
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_request_id BIGINT      NOT NULL,
    transaction_id     VARCHAR(64) NOT NULL,
    payment_methods    VARCHAR(40) NOT NULL,
    total_amount       BIGINT      NOT NULL,
    point_amount       BIGINT      NOT NULL,
    external_amount    BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_payments_booking_request UNIQUE (booking_request_id),
    CONSTRAINT uk_payments_transaction UNIQUE (transaction_id)
);

CREATE TABLE IF NOT EXISTS point_history
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_request_id BIGINT      NOT NULL,
    type               VARCHAR(10) NOT NULL,
    amount             BIGINT      NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_point_history_order_type UNIQUE (booking_request_id, type)
);
