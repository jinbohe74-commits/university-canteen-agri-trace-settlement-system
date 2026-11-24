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

    /**
     * 修复 P1-12：结算审核驳回后，把订单从「待结算(80)」原子回退到「已送达(60)」，
     * 允许重新入库确认。带 status 与 version 条件，避免与其他流转并发冲突。
     */
    @Update("UPDATE orders SET status = 60, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 80 AND version = #{version}")
    int reopenForInbound(@Param("id") Long id, @Param("version") Integer version);

    @Select("SELECT DATE_FORMAT(create_time,'%Y-%m') m, COUNT(*) c FROM orders " +
            "WHERE create_time >= #{from} GROUP BY m ORDER BY m")
    List<Map<String, Object>> countByMonth(@Param("from") LocalDateTime from);

    @Select("SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE status NOT IN (41,99)")
    BigDecimal sumValidAmount();

    /** 农户本月真实成交笔数：本月创建、已接单起算、剔除质检不通过/已取消。
        不能用「在售货源数 × 常数」估——那个数字和库里订单对不上，属于假数据。 */
    @Select("SELECT COUNT(*) FROM orders WHERE farmer_id = #{farmerId} " +
            "AND create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01') " +
            "AND create_time < DATE_ADD(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 1 MONTH) " +
            "AND status >= 20 AND status NOT IN (41, 99)")
    Long countMonthDeals(@Param("farmerId") Long farmerId);
}
