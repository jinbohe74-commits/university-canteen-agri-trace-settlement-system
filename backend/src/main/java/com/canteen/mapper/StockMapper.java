package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.Stock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface StockMapper extends BaseMapper<Stock> {

    /** 入库加库存：单条原子 upsert，避免并发下先查后写的丢更新 */
    @Insert("INSERT INTO stock (canteen_id, product_id, quantity, unit, safety_qty) " +
            "VALUES (#{canteenId}, #{productId}, #{qty}, #{unit}, 0) " +
            "ON DUPLICATE KEY UPDATE quantity = quantity + #{qty}")
    int addStock(@Param("canteenId") Long canteenId,
                 @Param("productId") Long productId,
                 @Param("qty") BigDecimal qty,
                 @Param("unit") String unit);

    /** 出库扣减：WHERE 带数量条件，影响行数为 0 说明库存不足 */
    @Update("UPDATE stock SET quantity = quantity - #{qty} " +
            "WHERE canteen_id = #{canteenId} AND product_id = #{productId} AND quantity >= #{qty}")
    int deductStock(@Param("canteenId") Long canteenId,
                    @Param("productId") Long productId,
                    @Param("qty") BigDecimal qty);

    /** 在库清单 + 近 7 天 / 近 28 天真实领用量 */
    @Select("SELECT s.id, s.product_id AS productId, p.name AS productName, p.product_no AS productNo, " +
            "       pd.category_name AS categoryName, s.quantity, p.unit, s.safety_qty AS safetyQty, s.update_time AS updateTime, " +
            "       (SELECT IFNULL(SUM(o.quantity), 0) FROM stock_out o " +
            "         WHERE o.canteen_id = s.canteen_id AND o.product_id = s.product_id " +
            "           AND o.create_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)) AS weekOut, " +
            "       (SELECT IFNULL(SUM(o.quantity), 0) FROM stock_out o " +
            "         WHERE o.canteen_id = s.canteen_id AND o.product_id = s.product_id " +
            "           AND o.create_time >= DATE_SUB(NOW(), INTERVAL 28 DAY)) AS monthOut, " +
            // 领用流水不够时的兜底依据：同期真实到货量（稳态食堂的到货量约等于消耗量）
            "       (SELECT IFNULL(SUM(r.receive_quantity), 0) FROM inbound_record r " +
            "          JOIN orders od ON od.id = r.order_id " +
            "         WHERE od.canteen_id = s.canteen_id AND od.product_id = s.product_id " +
            "           AND r.inbound_time >= DATE_SUB(NOW(), INTERVAL 28 DAY)) AS monthIn, " +
            "       (SELECT MAX(o.create_time) FROM stock_out o " +
            "         WHERE o.canteen_id = s.canteen_id AND o.product_id = s.product_id) AS lastOutAt " +
            "  FROM stock s " +
            "  JOIN product p ON p.id = s.product_id " +
            "  LEFT JOIN product_category pd ON pd.id = p.category_id " +
            " WHERE s.canteen_id = #{canteenId} " +
            " ORDER BY (s.quantity <= s.safety_qty) DESC, s.quantity ASC")
    List<Map<String, Object>> listStock(@Param("canteenId") Long canteenId);

    /** 最近一次该品项的入库单价，用于给采购建议估个价 */
    @Select("SELECT o.unit_price FROM inbound_record r " +
            "  JOIN orders o ON o.id = r.order_id " +
            " WHERE o.product_id = #{productId} AND o.canteen_id = #{canteenId} " +
            " ORDER BY r.inbound_time DESC LIMIT 1")
    BigDecimal lastInboundPrice(@Param("canteenId") Long canteenId, @Param("productId") Long productId);
}
