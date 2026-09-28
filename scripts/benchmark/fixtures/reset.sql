-- Deterministic cleanup restricted to BENCH_USER_ accounts and reserved sessions.
SET NAMES utf8mb4;

DROP TEMPORARY TABLE IF EXISTS bench_user_ids;
CREATE TEMPORARY TABLE bench_user_ids (id BIGINT PRIMARY KEY);
INSERT INTO bench_user_ids (id)
SELECT id FROM sys_user WHERE LEFT(account, 11) = 'BENCH_USER_';

DROP TEMPORARY TABLE IF EXISTS bench_order_nos;
CREATE TEMPORARY TABLE bench_order_nos (
    order_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY,
    order_id BIGINT NOT NULL
);
INSERT INTO bench_order_nos (order_no, order_id)
SELECT o.order_no, o.id
FROM ticket_order o
JOIN bench_user_ids u ON u.id = o.user_id;

DROP TEMPORARY TABLE IF EXISTS bench_event_ids;
CREATE TEMPORARY TABLE bench_event_ids (
    event_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY
);
INSERT INTO bench_event_ids (event_id)
SELECT e.event_id
FROM outbox_event e
JOIN bench_order_nos o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.aggregate_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE e.aggregate_type = 'ORDER';

DELETE c FROM consumed_event c JOIN bench_event_ids e
  ON CONVERT(e.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(c.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE e FROM outbox_event e JOIN bench_event_ids b
  ON CONVERT(b.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE r FROM refund_record r JOIN bench_order_nos o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(r.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE t FROM electronic_ticket t JOIN bench_order_nos o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(t.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE p FROM payment_record p JOIN bench_order_nos o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(p.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE s FROM order_seat s JOIN bench_order_nos o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(s.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE l FROM seat_lock l
WHERE l.schedule_id BETWEEN 910001 AND 910008
   OR l.user_id IN (SELECT id FROM bench_user_ids);
DELETE o FROM ticket_order o JOIN bench_order_nos b
  ON CONVERT(b.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci;
DELETE f FROM activity_follow f JOIN bench_user_ids u ON u.id = f.user_id;

UPDATE sys_user u JOIN bench_user_ids b ON b.id = u.id
SET u.points = 1000000, u.role = 'USER', u.deleted = 0,
    u.update_time = CURRENT_TIMESTAMP;

UPDATE activity_session
SET total_seats = 20000, available_seats = 20000, price = 1.00,
    status = 1, version = 0, deleted = 0,
    show_date = DATE_ADD(CURDATE(), INTERVAL 30 DAY),
    update_time = CURRENT_TIMESTAMP
WHERE id BETWEEN 910001 AND 910008;

DROP TEMPORARY TABLE bench_event_ids;
DROP TEMPORARY TABLE bench_order_nos;
DROP TEMPORARY TABLE bench_user_ids;
