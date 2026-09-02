package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.VenuePO;
import com.maoyan.dao.provider.CinemaSqlProvider;
import com.maoyan.domain.model.dto.CinemaQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

/**
 * 场馆 Mapper。
 *
 * <p>Phase 3A 已切换到底层 venue 表，上层 cinema API 暂时兼容。</p>
 */
@Mapper
public interface VenueMapper extends BaseMapper<VenuePO> {

    /**
     * 根据复合条件查询影院列表（支持品牌/服务/厅型/商圈多维过滤）
     */
    @SelectProvider(type = CinemaSqlProvider.class, method = "selectCinemaList")
    List<VenuePO> selectCinemaList(CinemaQueryDTO query);

    /**
     * 搜索影院 (模糊匹配名称/地址)
     */
    @Select("""
        SELECT * FROM venue
        WHERE deleted = 0 
          AND city_id = #{cityId}
          AND (nm LIKE CONCAT('%', #{kw}, '%') 
               OR addr LIKE CONCAT('%', #{kw}, '%'))
        ORDER BY sort_order, id
        LIMIT 20
    """)
    List<VenuePO> searchByKeyword(@Param("kw") String keyword, @Param("cityId") Long cityId);
}
