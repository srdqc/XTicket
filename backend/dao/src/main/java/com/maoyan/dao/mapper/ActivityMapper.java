package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.ActivityPO;
import com.maoyan.dao.provider.MovieFilterSqlProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

/**
 * 活动 Mapper。
 *
 * <p>Phase 3A 已切换到底层 activity 表，上层 movie API 暂时兼容。</p>
 */
@Mapper
public interface ActivityMapper extends BaseMapper<ActivityPO> {

    /**
     * 获取热映电影ID列表
     */
    @Select("SELECT id FROM activity WHERE movie_status = 1 AND deleted = 0 ORDER BY sort_order, id")
    List<Long> selectHotMovieIds();

    /**
     * 获取即将上映电影ID列表
     */
    @Select("SELECT id FROM activity WHERE movie_status = 0 AND deleted = 0 ORDER BY sort_order, id")
    List<Long> selectComingMovieIds();

    /**
     * 根据ID列表批量查询
     */
    List<ActivityPO> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 搜索电影 (模糊匹配名称/演员/分类)
     */
    @Select("""
        SELECT * FROM activity
        WHERE deleted = 0 
          AND (nm LIKE CONCAT('%', #{kw}, '%') 
               OR enm LIKE CONCAT('%', #{kw}, '%')
               OR star LIKE CONCAT('%', #{kw}, '%')
               OR cat LIKE CONCAT('%', #{kw}, '%'))
        ORDER BY movie_status DESC, sort_order
        LIMIT 20
    """)
    List<ActivityPO> searchByKeyword(@Param("kw") String keyword);

    /**
     * 根据类型/地区/年份/状态筛选电影（动态SQL）
     */
    @SelectProvider(type = MovieFilterSqlProvider.class, method = "filterMovies")
    List<ActivityPO> filterMovies(@Param("movieStatus") Integer movieStatus,
                                  @Param("cat") String cat,
                                  @Param("src") String src,
                                  @Param("year") Integer year,
                                  @Param("sortBy") String sortBy,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /**
     * 统计筛选电影总数
     */
    @SelectProvider(type = MovieFilterSqlProvider.class, method = "countFilterMovies")
    long countFilterMovies(@Param("movieStatus") Integer movieStatus,
                           @Param("cat") String cat,
                           @Param("src") String src,
                           @Param("year") Integer year);
}
