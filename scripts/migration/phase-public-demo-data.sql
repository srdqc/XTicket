-- Idempotent migration for the original XTicket demo catalog only.
-- It intentionally does not touch orders, payments, tickets, refunds, locks,
-- outbox data, or benchmark fixture IDs (900001+).

DROP PROCEDURE IF EXISTS migrate_public_demo_data;
DELIMITER //
CREATE PROCEDURE migrate_public_demo_data()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    UPDATE activity
    SET nm = CASE id
            WHEN 1 THEN '星河音乐现场' WHEN 2 THEN '风野音乐节' WHEN 3 THEN '城市篮球邀请赛'
            WHEN 4 THEN '未来竞技挑战赛' WHEN 5 THEN '光影城市艺术展' WHEN 6 THEN '远方来信舞台剧'
            WHEN 7 THEN '仲夏室内音乐会' WHEN 8 THEN '山海脱口秀专场' WHEN 9 THEN '校园足球冠军赛'
            WHEN 10 THEN '城市跑步嘉年华' WHEN 11 THEN '自然声音沉浸展' WHEN 12 THEN '时间邮局音乐剧'
            WHEN 13 THEN '破晓街舞大赛' WHEN 14 THEN '月面科学互动展' WHEN 15 THEN '人间烟火市集'
            WHEN 16 THEN '夜航电子音乐派对' WHEN 17 THEN '星际交响音乐会' WHEN 18 THEN '四季摄影展'
            WHEN 19 THEN '冰雪技巧公开赛' WHEN 20 THEN '童梦亲子音乐会' WHEN 21 THEN '高山探索分享会'
            WHEN 22 THEN '清醒喜剧之夜' WHEN 23 THEN '海风钢琴独奏会' WHEN 24 THEN '无界设计论坛'
            WHEN 25 THEN '西游新编儿童剧' WHEN 26 THEN '明日科技体验展' WHEN 27 THEN '春雨民谣音乐会'
            WHEN 28 THEN '龙舟城市挑战赛' WHEN 29 THEN '荒野生存公开课' WHEN 30 THEN '青春戏剧季'
            WHEN 31 THEN '天空摄影工作坊' WHEN 32 THEN '城市推理剧场' WHEN 33 THEN '银河科学讲堂'
            WHEN 34 THEN '功夫与身体剧场' WHEN 35 THEN '极速卡丁车公开赛' WHEN 36 THEN '梦境插画展'
            WHEN 37 THEN '数字安全公开课' WHEN 38 THEN '江湖国风音乐节' WHEN 39 THEN '家庭科学嘉年华'
            WHEN 40 THEN '城市漫游艺术周' WHEN 41 THEN '幻影魔术现场' WHEN 42 THEN '草原民歌音乐会'
            WHEN 43 THEN '巨兽自然科普展' WHEN 44 THEN '青春书信朗读会' WHEN 45 THEN '公共法律生活课'
            ELSE nm END,
        enm = CONCAT('XTicket Event ', LPAD(id, 2, '0')),
        img = CONCAT('/images/events/event-', LPAD(id, 2, '0'), '.svg'),
        star = CASE MOD(id - 1, 6)
            WHEN 0 THEN '城市艺术团' WHEN 1 THEN '青年活动联盟' WHEN 2 THEN '城市代表队'
            WHEN 3 THEN '公共文化计划' WHEN 4 THEN '联合策展团队' ELSE '社区创意社群' END,
        cat = CASE MOD(id - 1, 8)
            WHEN 0 THEN '演唱会' WHEN 1 THEN '音乐节' WHEN 2 THEN '体育赛事' WHEN 3 THEN '电竞赛事'
            WHEN 4 THEN '艺术展' WHEN 5 THEN '舞台剧' WHEN 6 THEN '音乐会' ELSE '文化活动' END,
        src = CASE MOD(id - 1, 6)
            WHEN 0 THEN '北京' WHEN 1 THEN '上海' WHEN 2 THEN '广州'
            WHEN 3 THEN '深圳' WHEN 4 THEN '成都' ELSE '杭州' END,
        pub_desc = CASE WHEN id <= 25 THEN '本周售票中' ELSE '即将开售' END,
        dra = 'XTicket 综合活动票务演示项目。',
        vd = '', photos = '[]', pn = 0,
        show_info = CASE WHEN id <= 25 THEN '近期有场 · 余票充足' ELSE NULL END,
        coming_title = CASE WHEN id <= 25 THEN NULL ELSE '即将开售' END
    WHERE id BETWEEN 1 AND 45
      AND (img LIKE 'https://picsum.photos/seed/movie%' OR img LIKE '/images/events/event-%');

    UPDATE cinema_brand SET name = CASE id
        WHEN 1 THEN '城市文体中心' WHEN 2 THEN '公共文化空间' WHEN 3 THEN '青年活动中心'
        WHEN 4 THEN '社区文体中心' WHEN 5 THEN '国际交流中心' WHEN 6 THEN '城市剧院联盟'
        WHEN 7 THEN '现场演出联盟' WHEN 8 THEN '体育场馆联盟' WHEN 9 THEN '会展中心联盟'
        WHEN 10 THEN '高校场馆联盟' ELSE name END
    WHERE id BETWEEN 1 AND 10;

    UPDATE service_type SET name = CASE id
        WHEN 1 THEN '可退票' WHEN 2 THEN '可改签' WHEN 3 THEN '无障碍通道' WHEN 4 THEN '停车场'
        ELSE name END
    WHERE id BETWEEN 1 AND 4;

    UPDATE hall_type SET name = CASE id
        WHEN 1 THEN '主舞台' WHEN 2 THEN '音乐厅' WHEN 3 THEN '沉浸式空间'
        WHEN 4 THEN '中心舞台' WHEN 5 THEN '多功能厅' WHEN 6 THEN '实验剧场' ELSE name END
    WHERE id BETWEEN 1 AND 6;

    UPDATE venue
    SET nm = CASE id
            WHEN 1 THEN '朝阳城市体育馆' WHEN 2 THEN '三里屯文化艺术中心' WHEN 3 THEN '望京国际会展中心'
            WHEN 4 THEN '国贸城市剧院' WHEN 5 THEN '五棵松体育中心' WHEN 6 THEN '五道口Live House'
            WHEN 7 THEN '中关村青年活动中心' WHEN 8 THEN '西直门公共文化馆' WHEN 9 THEN '王府井音乐厅'
            WHEN 10 THEN '东直门演艺空间' WHEN 11 THEN '西单城市剧场' WHEN 12 THEN '西单实验剧场'
            WHEN 13 THEN '方庄社区体育馆' WHEN 14 THEN '大红门文化中心' WHEN 15 THEN '丽泽国际会议中心'
            WHEN 16 THEN '通州城市体育场' WHEN 17 THEN '通州北苑艺术中心' WHEN 18 THEN '通州社区活动中心'
            WHEN 19 THEN '望京新荟演艺空间' WHEN 20 THEN '朝阳门Live House' WHEN 21 THEN '安贞体育文化中心'
            WHEN 22 THEN '石景山城市体育馆' WHEN 23 THEN '酒仙桥艺术空间' WHEN 24 THEN '中关村南大街音乐厅'
            WHEN 25 THEN '亦庄国际会展中心' ELSE nm END,
        addr = CONCAT('北京市演示地址', id, '号'),
        snack = 0,
        vip_tag = CASE WHEN MOD(id, 4) = 0 THEN '无障碍区' ELSE '' END,
        hall_types_json = '["多功能厅"]',
        card_promotion_tag = CASE WHEN MOD(id, 3) = 0 THEN '早鸟票' ELSE '' END
    WHERE id BETWEEN 1 AND 25;

    UPDATE activity_session
    SET hall_name = CASE hall_name
        WHEN 'IMAX厅' THEN '主舞台' WHEN '杜比全景声厅' THEN '音乐厅'
        WHEN '中国巨幕厅' THEN '中心舞台' WHEN '4DX厅' THEN '沉浸式空间'
        WHEN '杜比影院' THEN '实验剧场' WHEN '1号厅' THEN 'A馆'
        WHEN '2号厅' THEN 'B馆' WHEN '3号厅' THEN '多功能厅'
        WHEN '5号厅' THEN '实验剧场' ELSE hall_name END,
        lang = '现场'
    WHERE id BETWEEN 1 AND 40 AND activity_id BETWEEN 1 AND 5 AND venue_id BETWEEN 1 AND 9;

    UPDATE venue_hall
    SET hall_name = CASE hall_name
        WHEN 'IMAX厅' THEN '主舞台' WHEN '杜比全景声厅' THEN '音乐厅'
        WHEN '中国巨幕厅' THEN '中心舞台' WHEN '4DX厅' THEN '沉浸式空间'
        WHEN '杜比影院' THEN '实验剧场' WHEN '1号厅' THEN 'A馆'
        WHEN '2号厅' THEN 'B馆' WHEN '3号厅' THEN '多功能厅'
        WHEN '5号厅' THEN '实验剧场' ELSE hall_name END,
        hall_type = CASE hall_type
        WHEN 'IMAX' THEN '主舞台' WHEN '杜比全景声' THEN '音乐厅'
        WHEN '中国巨幕' THEN '中心舞台' WHEN '4DX' THEN '沉浸式空间'
        WHEN '杜比影院' THEN '实验剧场' WHEN '普通厅' THEN '多功能厅' ELSE hall_type END
    WHERE id BETWEEN 1 AND 16 AND venue_id BETWEEN 1 AND 9;

    COMMIT;
END//
DELIMITER ;

CALL migrate_public_demo_data();
DROP PROCEDURE migrate_public_demo_data;
