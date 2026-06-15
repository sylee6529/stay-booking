INSERT IGNORE INTO promotion_products (
    id, name, price, total_quantity, available_quantity, reserved_quantity, sold_quantity,
    checkin_date, checkout_date, open_at
)
VALUES (
    1, '제주 오션뷰 스테이 - 자정 오픈 특가', 150000, 10, 10, 0, 0,
    '2026-07-01', '2026-07-02', '2026-01-01 00:00:00'
);

INSERT IGNORE INTO user_points (user_id, balance)
VALUES (1, 50000),
       (2, 200000);
