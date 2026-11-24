package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.SupplySource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface SupplySourceMapper extends BaseMapper<SupplySource> {

    /**
     * 修复 P1-10：库存扣减下沉到单条原子 UPDATE，WHERE 带数量条件并检查影响行数，
     * 杜绝「先读后写」在并发下超卖。status 赋值放最前，引用的是扣减前的 quantity。
     */
    @Update("UPDATE supply_source SET status = IF(quantity <= #{qty}, '已售罄', status), " +
            "quantity = quantity - #{qty}, sold_quantity = IFNULL(sold_quantity, 0) + #{qty}, " +
            "update_time = NOW() " +
            "WHERE id = #{id} AND status = '正常' AND quantity >= #{qty}")
    int deductStock(@Param("id") Long id, @Param("qty") BigDecimal qty);
}
