package com.maoyan.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maoyan.domain.model.po.PaymentRecordPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 支付记录 Mapper.
 */
@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecordPO> {

    @Select("SELECT COUNT(*) FROM payment_record WHERE order_no = #{orderNo} AND deleted = 0")
    int countByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM payment_record WHERE order_no = #{orderNo} AND status = 'SUCCESS' AND deleted = 0 LIMIT 1")
    PaymentRecordPO selectSuccessfulByOrderNo(@Param("orderNo") String orderNo);
}
