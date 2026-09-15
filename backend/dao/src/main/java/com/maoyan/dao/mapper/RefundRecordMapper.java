package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.RefundRecordPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RefundRecordMapper extends BaseMapper<RefundRecordPO> {

    @Select("SELECT * FROM refund_record WHERE order_no = #{orderNo} AND deleted = 0 LIMIT 1")
    RefundRecordPO selectByOrderNo(@Param("orderNo") String orderNo);
}
