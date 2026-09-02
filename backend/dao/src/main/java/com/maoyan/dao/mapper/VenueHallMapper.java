package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.VenueHallPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 场馆会场 Mapper。
 */
@Mapper
public interface VenueHallMapper extends BaseMapper<VenueHallPO> {

    /**
     * 根据场馆ID和会场名查找座位布局
     */
    @Select("SELECT * FROM venue_hall WHERE venue_id = #{venueId} AND hall_name = #{hallName} AND deleted = 0 LIMIT 1")
    VenueHallPO selectByVenueAndHall(@Param("venueId") Long venueId, @Param("hallName") String hallName);

    /**
     * 查询场馆所有会场
     */
    @Select("SELECT * FROM venue_hall WHERE venue_id = #{venueId} AND deleted = 0 ORDER BY id")
    List<VenueHallPO> selectByVenueId(@Param("venueId") Long venueId);
}
