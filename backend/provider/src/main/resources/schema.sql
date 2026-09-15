-- =====================================================
-- 猫眼后端 - 数据库表结构 (H2 MySQL 兼容模式)
-- =====================================================

-- 活动表（Phase 3A 底层事实源，部分电影兼容字段暂留）
CREATE TABLE IF NOT EXISTS activity (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    nm              VARCHAR(200)  NOT NULL        COMMENT '电影名',
    enm             VARCHAR(200)                  COMMENT '英文名',
    img             VARCHAR(500)                  COMMENT '海报URL',
    sc              DECIMAL(3,1)  DEFAULT 0       COMMENT '评分',
    star            VARCHAR(500)                  COMMENT '演员',
    cat             VARCHAR(200)                  COMMENT '分类标签',
    src             VARCHAR(200)                  COMMENT '来源/国家',
    dur             INT                           COMMENT '时长(分钟)',
    pub_desc        VARCHAR(200)                  COMMENT '上映描述',
    dra             TEXT                          COMMENT '剧情简介',
    wish            INT           DEFAULT 0       COMMENT '想看人数',
    vd              VARCHAR(500)                  COMMENT '预告片URL',
    photos          TEXT                          COMMENT '剧照JSON数组',
    pn              INT           DEFAULT 0       COMMENT '剧照总数',
    show_info       VARCHAR(200)                  COMMENT '上映信息',
    coming_title    VARCHAR(100)                  COMMENT '即将上映标题',
    movie_status    INT           DEFAULT 0       COMMENT '0=即将上映,1=正在热映,2=已下映',
    global_released INT           DEFAULT 0       COMMENT '是否已上映:0=否,1=是',
    release_year    INT           DEFAULT 2026    COMMENT '上映年份',
    sort_order      INT           DEFAULT 0       COMMENT '排序权重',
    version         INT           DEFAULT 0       COMMENT '乐观锁版本号',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted         INT           DEFAULT 0
);

-- 城市表
CREATE TABLE IF NOT EXISTS city (
    id  BIGINT PRIMARY KEY               COMMENT '城市ID',
    nm  VARCHAR(50)  NOT NULL             COMMENT '城市名',
    py  VARCHAR(100)                      COMMENT '拼音'
);

-- 场馆表（Phase 3A 底层事实源，部分影院兼容字段暂留）
CREATE TABLE IF NOT EXISTS venue (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    nm                  VARCHAR(200) NOT NULL  COMMENT '影院名称',
    addr                VARCHAR(500)           COMMENT '地址',
    city_id             BIGINT       NOT NULL  COMMENT '城市ID',
    brand_id            BIGINT                 COMMENT '品牌ID',
    district_id         BIGINT                 COMMENT '行政区ID',
    area_id             BIGINT                 COMMENT '商圈ID',
    distance            VARCHAR(50)            COMMENT '距离描述',
    allow_refund        INT DEFAULT 0          COMMENT '可退票',
    endorse             INT DEFAULT 0          COMMENT '可改签',
    snack               INT DEFAULT 0          COMMENT '有小吃',
    vip_tag             VARCHAR(100)           COMMENT 'VIP标签',
    hall_types_json     VARCHAR(500)           COMMENT '厅型JSON数组',
    card_promotion_tag  VARCHAR(200)           COMMENT '促销标签',
    sort_order          INT DEFAULT 0          COMMENT '排序权重',
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             INT DEFAULT 0
);

-- 影院品牌表
CREATE TABLE IF NOT EXISTS cinema_brand (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    count INT DEFAULT 0
);

-- 行政区/商圈表
CREATE TABLE IF NOT EXISTS district (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    city_id   BIGINT  NOT NULL,
    parent_id BIGINT  DEFAULT 0   COMMENT '0=顶级行政区',
    count     INT     DEFAULT 0
);

-- 地铁线路/站点表
CREATE TABLE IF NOT EXISTS subway (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name      VARCHAR(100) NOT NULL,
    city_id   BIGINT  NOT NULL,
    parent_id BIGINT  DEFAULT 0   COMMENT '0=地铁线,>0=站点',
    count     INT     DEFAULT 0
);

-- 服务类型表
CREATE TABLE IF NOT EXISTS service_type (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    count INT DEFAULT 0
);

-- 影厅类型表
CREATE TABLE IF NOT EXISTS hall_type (
    id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    count INT DEFAULT 0
);

-- 场馆-服务关联表
CREATE TABLE IF NOT EXISTS venue_service_rel (
    venue_id   BIGINT NOT NULL,
    service_id BIGINT NOT NULL,
    PRIMARY KEY (venue_id, service_id)
);

-- 场馆-厅型关联表
CREATE TABLE IF NOT EXISTS venue_hall_type_rel (
    venue_id     BIGINT NOT NULL,
    hall_type_id BIGINT NOT NULL,
    PRIMARY KEY (venue_id, hall_type_id)
);

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account        VARCHAR(100) NOT NULL UNIQUE,
    password       VARCHAR(200) NOT NULL,
    user_nick      VARCHAR(100),
    user_head_img  VARCHAR(500),
    points         INT DEFAULT 0        COMMENT '用户积分(1积分=1元)',
    role           VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT 'USER/CHECKIN_STAFF',
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        INT DEFAULT 0
);

-- 活动场次表（防超卖核心场景）
CREATE TABLE IF NOT EXISTS activity_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id     BIGINT        NOT NULL      COMMENT '关联活动',
    venue_id        BIGINT        NOT NULL      COMMENT '关联场馆',
    hall_name       VARCHAR(50)                 COMMENT '影厅名称',
    show_date       DATE          NOT NULL      COMMENT '放映日期',
    show_time       VARCHAR(10)   NOT NULL      COMMENT '放映时间 HH:mm',
    end_time        VARCHAR(10)                 COMMENT '散场时间 HH:mm',
    lang            VARCHAR(30)   DEFAULT '国语' COMMENT '语言版本',
    total_seats     INT           NOT NULL DEFAULT 120 COMMENT '总座位数',
    available_seats INT           NOT NULL DEFAULT 120 COMMENT '剩余可售座位',
    price           DECIMAL(10,2) NOT NULL DEFAULT 39.90 COMMENT '单价',
    status          INT           DEFAULT 1     COMMENT '1=可售 0=停售',
    version         INT           DEFAULT 0     COMMENT '乐观锁版本号',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted         INT           DEFAULT 0
);

-- 订单表
CREATE TABLE IF NOT EXISTS ticket_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(64)   NOT NULL UNIQUE COMMENT '订单编号',
    user_id         BIGINT        NOT NULL      COMMENT '用户ID',
    schedule_id     BIGINT        NOT NULL      COMMENT '场次ID',
    lock_token      VARCHAR(64)   NOT NULL      COMMENT '锁座令牌',
    movie_name      VARCHAR(200)                COMMENT '电影名（冗余）',
    cinema_name     VARCHAR(200)                COMMENT '影院名（冗余）',
    hall_name       VARCHAR(50)                 COMMENT '厅名（冗余）',
    show_time       VARCHAR(30)                 COMMENT '放映时间（冗余）',
    seat_count      INT           NOT NULL DEFAULT 1 COMMENT '座位数',
    seats_info      VARCHAR(500)                COMMENT '座位信息(如:5排3座,5排4座)',
    unit_price      DECIMAL(10,2)               COMMENT '单价',
    total_price     DECIMAL(10,2)               COMMENT '总价',
    status          INT           DEFAULT 0     COMMENT '0=待支付 1=已支付 2=已取消 3=已退款',
    expire_time     TIMESTAMP     NULL          COMMENT '支付截止时间',
    pay_time        TIMESTAMP     NULL          COMMENT '支付时间',
    cancel_time     TIMESTAMP     NULL          COMMENT '取消时间',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted         INT           DEFAULT 0
);

CREATE TABLE IF NOT EXISTS payment_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no      VARCHAR(64)   NOT NULL      COMMENT '支付记录编号',
    order_no        VARCHAR(64)   NOT NULL      COMMENT '订单编号',
    user_id         BIGINT        NOT NULL      COMMENT '用户ID',
    amount          DECIMAL(10,2) NOT NULL      COMMENT '支付金额',
    channel         VARCHAR(32)   NOT NULL      COMMENT '支付渠道: MOCK_POINTS',
    status          VARCHAR(20)   NOT NULL      COMMENT '支付状态: SUCCESS',
    paid_at         TIMESTAMP     NOT NULL      COMMENT '支付成功时间',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted         INT           DEFAULT 0
);

-- 用户想看记录表
CREATE TABLE IF NOT EXISTS activity_follow (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT        NOT NULL      COMMENT '用户ID',
    activity_id     BIGINT        NOT NULL      COMMENT '活动ID',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- 场馆会场座位布局表（物理座位图：行列+过道+情侣座）
CREATE TABLE IF NOT EXISTS venue_hall (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    venue_id        BIGINT        NOT NULL      COMMENT '关联场馆',
    hall_name       VARCHAR(50)   NOT NULL      COMMENT '影厅名称',
    seat_rows       INT           NOT NULL DEFAULT 10  COMMENT '座位行数',
    seat_cols       INT           NOT NULL DEFAULT 14  COMMENT '座位列数',
    aisle_after_col VARCHAR(50)   DEFAULT ''            COMMENT '过道位于第N列之后,逗号分隔',
    couple_rows     VARCHAR(50)   DEFAULT ''            COMMENT '情侣座行号,逗号分隔',
    disabled_seats  TEXT                                COMMENT '不可用座位JSON [[row,col],...]',
    hall_type       VARCHAR(50)   DEFAULT '普通厅'       COMMENT '厅类型',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    deleted         INT           DEFAULT 0
);

-- 座位锁定表（高并发锁座核心表 — 悲观锁/分布式锁配合使用）
CREATE TABLE IF NOT EXISTS seat_lock (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id     BIGINT        NOT NULL      COMMENT '场次ID',
    row_num         INT           NOT NULL      COMMENT '行号',
    col_num         INT           NOT NULL      COMMENT '列号',
    user_id         BIGINT        NOT NULL      COMMENT '锁座用户ID',
    lock_token      VARCHAR(64)   NOT NULL      COMMENT '锁座批次令牌',
    order_no        VARCHAR(64)   NULL          COMMENT '绑定的订单号',
    lock_until      TIMESTAMP     NOT NULL      COMMENT '锁定到期时间',
    status          INT           DEFAULT 1     COMMENT '1=锁定中 0=已释放 2=已购买',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

-- 订单座位明细表（一对多：一个订单对应多个座位）
CREATE TABLE IF NOT EXISTS order_seat (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL      COMMENT '订单ID',
    order_no        VARCHAR(64)   NOT NULL      COMMENT '订单编号',
    schedule_id     BIGINT        NOT NULL      COMMENT '场次ID',
    row_num         INT           NOT NULL      COMMENT '行号',
    col_num         INT           NOT NULL      COMMENT '列号',
    seat_label      VARCHAR(20)   NOT NULL      COMMENT '座位标签(如5排3座)',
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS electronic_ticket (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no       VARCHAR(40) NOT NULL COMMENT '电子票业务编号',
    order_seat_id   BIGINT      NOT NULL COMMENT '已确认订单座位ID',
    order_no        VARCHAR(64) NOT NULL COMMENT '订单编号',
    user_id         BIGINT      NOT NULL COMMENT '电子票所属用户ID',
    session_id      BIGINT      NOT NULL COMMENT '活动场次ID',
    status          INT         NOT NULL DEFAULT 0 COMMENT '0=已签发 1=已核销 2=已作废',
    issued_at       TIMESTAMP   NOT NULL COMMENT '签发时间',
    used_at         TIMESTAMP   NULL COMMENT '核销时间',
    checked_by      BIGINT      NULL COMMENT '首次核销工作人员用户ID',
    invalidated_at  TIMESTAMP   NULL COMMENT '作废时间',
    create_time     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    deleted         INT         DEFAULT 0
);

-- ==================== 索引 ====================
CREATE INDEX IF NOT EXISTS idx_activity_status ON activity(movie_status, deleted, sort_order);
CREATE INDEX IF NOT EXISTS idx_activity_wish   ON activity(wish DESC);
CREATE INDEX IF NOT EXISTS idx_venue_city  ON venue(city_id, deleted, sort_order);
CREATE INDEX IF NOT EXISTS idx_venue_brand ON venue(brand_id);
CREATE INDEX IF NOT EXISTS idx_district_city ON district(city_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_subway_city  ON subway(city_id, parent_id);
CREATE INDEX IF NOT EXISTS idx_user_account ON sys_user(account);
CREATE INDEX IF NOT EXISTS idx_session_activity ON activity_session(activity_id, show_date, deleted);
CREATE INDEX IF NOT EXISTS idx_session_venue ON activity_session(venue_id, show_date, deleted);
CREATE INDEX IF NOT EXISTS idx_order_user   ON ticket_order(user_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_order_status_expire ON ticket_order(status, expire_time);
CREATE INDEX IF NOT EXISTS idx_order_no     ON ticket_order(order_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_order_lock_token ON ticket_order(lock_token);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_record_payment_no ON payment_record(payment_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_record_order_no ON payment_record(order_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_activity_follow_unique ON activity_follow(user_id, activity_id);
CREATE INDEX IF NOT EXISTS idx_hall_venue ON venue_hall(venue_id, deleted);
CREATE UNIQUE INDEX IF NOT EXISTS idx_hall_unique ON venue_hall(venue_id, hall_name, deleted);
CREATE INDEX IF NOT EXISTS idx_seat_lock_schedule ON seat_lock(schedule_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_seat_lock_unique ON seat_lock(schedule_id, row_num, col_num);
CREATE INDEX IF NOT EXISTS idx_seat_lock_user ON seat_lock(user_id, status);
CREATE INDEX IF NOT EXISTS idx_seat_lock_token ON seat_lock(lock_token);
CREATE INDEX IF NOT EXISTS idx_seat_lock_expire ON seat_lock(lock_until, status);
CREATE INDEX IF NOT EXISTS idx_order_seat_order ON order_seat(order_id);
CREATE INDEX IF NOT EXISTS idx_order_seat_schedule ON order_seat(schedule_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_order_seat_unique ON order_seat(schedule_id, row_num, col_num);
CREATE UNIQUE INDEX IF NOT EXISTS uq_electronic_ticket_no ON electronic_ticket(ticket_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_electronic_ticket_order_seat ON electronic_ticket(order_seat_id);
CREATE INDEX IF NOT EXISTS idx_electronic_ticket_user ON electronic_ticket(user_id, status);
CREATE INDEX IF NOT EXISTS idx_electronic_ticket_order ON electronic_ticket(order_no);
CREATE INDEX IF NOT EXISTS idx_electronic_ticket_session ON electronic_ticket(session_id, status);
CREATE INDEX IF NOT EXISTS idx_activity_year ON activity(release_year, movie_status, deleted);
