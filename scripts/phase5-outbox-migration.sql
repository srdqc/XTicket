USE xticket;

CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(36)   NOT NULL,
    aggregate_type  VARCHAR(40)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(40)   NOT NULL,
    topic           VARCHAR(120)  NOT NULL,
    tag             VARCHAR(80)   NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    retry_count     INT           NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP     NOT NULL,
    published_at    TIMESTAMP     NULL,
    last_error      VARCHAR(1000) NULL,
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uq_outbox_event_id (event_id),
    INDEX idx_outbox_publish (status, next_retry_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS consumed_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    consumer_group  VARCHAR(120) NOT NULL,
    event_id        VARCHAR(36)  NOT NULL,
    consumed_at     TIMESTAMP    NOT NULL,
    UNIQUE INDEX uq_consumed_event_group_event (consumer_group, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
