package com.maoyan.domain.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maoyan.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 场馆会场 - 座位布局实体
 * <p>
 * 设计理念（大厂思路）：
 * 物理会场的座位布局信息独立存储，与场次（activity_session）通过 venue_id + hall_name 关联。
 * 支持：行列定义、过道位置、情侣座、不可用座位等复杂布局。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("venue_hall")
public class VenueHallPO extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 场馆ID */
    private Long venueId;

    /** 会场名称 */
    private String hallName;

    /** 座位总行数 */
    private Integer seatRows;

    /** 座位总列数 */
    private Integer seatCols;

    /** 过道在第N列之后(逗号分隔)，如 "3,11" 表示第3列和第11列后有过道 */
    private String aisleAfterCol;

    /** 情侣座行号(逗号分隔)，如 "10" 表示第10行是情侣座 */
    private String coupleRows;

    /** 不可用座位 JSON 数组 [[row,col],...] */
    private String disabledSeats;

    /** 会场类型 */
    private String hallType;
}
