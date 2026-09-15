-- Run once with business traffic stopped. Existing order_seat rows are active sales.
SELECT COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'order_seat'
  AND COLUMN_NAME = 'active_sale_marker';

ALTER TABLE order_seat
    ADD COLUMN active_sale_marker TINYINT NULL DEFAULT 1
        COMMENT '1=当前有效售座 NULL=退款历史售座' AFTER seat_label;

SELECT COUNT(*) AS unexpected_historical_rows
FROM order_seat
WHERE active_sale_marker IS NULL;

ALTER TABLE order_seat
    DROP INDEX idx_order_seat_unique,
    ADD UNIQUE INDEX idx_order_seat_active_unique
        (schedule_id, row_num, col_num, active_sale_marker);

ALTER TABLE ticket_order
    ADD COLUMN refund_time DATETIME NULL COMMENT '退款时间' AFTER cancel_time;

CREATE TABLE refund_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL COMMENT '退款记录编号',
    order_no VARCHAR(64) NOT NULL COMMENT '订单编号',
    payment_no VARCHAR(64) NOT NULL COMMENT '原支付记录编号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    amount DECIMAL(10,2) NOT NULL COMMENT '退款金额',
    points INT NOT NULL COMMENT '返还积分',
    status VARCHAR(20) NOT NULL COMMENT '退款状态: SUCCESS',
    refunded_at DATETIME NOT NULL COMMENT '退款成功时间',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0,
    UNIQUE INDEX uq_refund_record_refund_no (refund_no),
    UNIQUE INDEX uq_refund_record_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
