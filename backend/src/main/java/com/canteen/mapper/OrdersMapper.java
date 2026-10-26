package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.Orders;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 乐观锁更新：version 不匹配则返回 0，由 Service 抛出并发冲突提示
     */
    @Update("UPDATE orders SET status = #{target}, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND version = #{version}")
    int updateStatusWithVersion(@Param("id") Long id, @Param("target") Integer target,
                                @Param("version") Integer version);

    @Update("UPDATE orders SET batch_no = #{batchNo}, update_time = NOW() WHERE id = #{id}")
    int updateBatchNo(@Param("id") Long id, @Param("batchNo") String batchNo);

    @Update("UPDATE orders SET actual_delivery = #{time}, update_time = NOW() WHERE id = #{id}")
    int updateActualDelivery(@Param("id") Long id, @Param("time") LocalDateTime time);

    @Select("SELECT DATE_FORMAT(create_time,'%Y-%m') m, COUNT(*) c FROM orders " +
            "WHERE create_time >= #{from} GROUP BY m ORDER BY m")
    List<Map<String, Object>> countByMonth(@Param("from") LocalDateTime from);

    @Select("SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE status NOT IN (41,99)")
    BigDecimal sumValidAmount();
}
