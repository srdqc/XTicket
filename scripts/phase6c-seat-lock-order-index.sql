SET NAMES utf8mb4;

ALTER TABLE seat_lock
    DROP INDEX idx_seat_lock_order_status,
    ADD INDEX idx_seat_lock_order (order_no);
