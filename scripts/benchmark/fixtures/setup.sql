-- XTicket benchmark fixture. Reserved IDs and BENCH_ prefixes isolate all rows.
SET NAMES utf8mb4;

INSERT INTO activity (
    id, nm, enm, img, sc, star, cat, src, dur, pub_desc, dra, wish,
    photos, pn, show_info, movie_status, global_released, release_year,
    sort_order, version, deleted
) VALUES (
    900001, 'BENCH_ACTIVITY_001', 'BENCH_ACTIVITY_001', '', 9.0, '',
    'BENCHMARK', 'SYNTHETIC', 60, 'Synthetic benchmark fixture',
    'Synthetic data for local reproducible benchmark only.', 0,
    '[]', 0, 'BENCHMARK', 1, 1, YEAR(CURDATE()), 900001, 0, 0
) ON DUPLICATE KEY UPDATE
    nm = VALUES(nm), enm = VALUES(enm), cat = VALUES(cat), src = VALUES(src),
    deleted = 0, update_time = CURRENT_TIMESTAMP;

INSERT INTO venue (
    id, nm, addr, city_id, distance, allow_refund, endorse, snack,
    hall_types_json, sort_order, deleted
) VALUES (
    900001, 'BENCH_VENUE_001', 'Synthetic benchmark venue', 1, '0m',
    1, 0, 0, '["BENCHMARK"]', 900001, 0
) ON DUPLICATE KEY UPDATE
    nm = VALUES(nm), addr = VALUES(addr), deleted = 0,
    update_time = CURRENT_TIMESTAMP;

INSERT INTO venue_hall (
    id, venue_id, hall_name, seat_rows, seat_cols, aisle_after_col,
    couple_rows, disabled_seats, hall_type, deleted
) VALUES
    (920001, 900001, 'BENCH_HALL_001', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920002, 900001, 'BENCH_HALL_002', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920003, 900001, 'BENCH_HALL_003', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920004, 900001, 'BENCH_HALL_004', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920005, 900001, 'BENCH_HALL_005', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920006, 900001, 'BENCH_HALL_006', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920007, 900001, 'BENCH_HALL_007', 100, 200, '', '', '[]', 'BENCHMARK', 0),
    (920008, 900001, 'BENCH_HALL_008', 100, 200, '', '', '[]', 'BENCHMARK', 0)
ON DUPLICATE KEY UPDATE
    venue_id = VALUES(venue_id), hall_name = VALUES(hall_name),
    seat_rows = VALUES(seat_rows), seat_cols = VALUES(seat_cols),
    disabled_seats = '[]', deleted = 0, update_time = CURRENT_TIMESTAMP;

INSERT INTO activity_session (
    id, activity_id, venue_id, hall_name, show_date, show_time, end_time,
    lang, total_seats, available_seats, price, status, version, deleted
) VALUES
    (910001, 900001, 900001, 'BENCH_HALL_001', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '10:00', '11:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910002, 900001, 900001, 'BENCH_HALL_002', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '11:00', '12:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910003, 900001, 900001, 'BENCH_HALL_003', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '12:00', '13:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910004, 900001, 900001, 'BENCH_HALL_004', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '13:00', '14:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910005, 900001, 900001, 'BENCH_HALL_005', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '14:00', '15:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910006, 900001, 900001, 'BENCH_HALL_006', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '15:00', '16:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910007, 900001, 900001, 'BENCH_HALL_007', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '16:00', '17:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0),
    (910008, 900001, 900001, 'BENCH_HALL_008', DATE_ADD(CURDATE(), INTERVAL 30 DAY), '17:00', '18:00', 'BENCH', 20000, 20000, 1.00, 1, 0, 0)
ON DUPLICATE KEY UPDATE
    activity_id = VALUES(activity_id), venue_id = VALUES(venue_id),
    hall_name = VALUES(hall_name), show_date = VALUES(show_date),
    total_seats = 20000, available_seats = 20000, price = 1.00,
    status = 1, version = 0, deleted = 0, update_time = CURRENT_TIMESTAMP;
