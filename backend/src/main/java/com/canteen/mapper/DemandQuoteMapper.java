package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.canteen.entity.DemandQuote;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface DemandQuoteMapper extends BaseMapper<DemandQuote> {

    /** 农户端的「需求大厅」：只看仍在处理中、未过截止时间的需求 */
    @Select("<script>" +
            "SELECT d.id, d.demand_no AS demandNo, d.quantity, d.expected_price AS expectedPrice," +
            "       d.deadline, d.delivery_mode AS deliveryMode, d.settlement_mode AS settlementMode," +
            "       d.quality_req AS qualityReq, d.quote_count AS quoteCount, d.status," +
            "       d.create_time AS createTime, pd.name AS productName, pd.unit, pd.category_id AS categoryId," +
            "       d.canteen_id AS canteenId, c.name AS canteenName, c.campus AS canteenAddress," +
            "       (SELECT COUNT(*) FROM demand_quote q WHERE q.demand_id = d.id) AS realQuoteCount," +
            "       (SELECT q2.price FROM demand_quote q2 WHERE q2.demand_id = d.id AND q2.farmer_id = #{farmerId}) AS myPrice," +
            "       (SELECT q3.status FROM demand_quote q3 WHERE q3.demand_id = d.id AND q3.farmer_id = #{farmerId}) AS myStatus" +
            "  FROM purchase_demand d" +
            "  LEFT JOIN product pd ON pd.id = d.product_id" +
            "  LEFT JOIN canteen c ON c.id = d.canteen_id" +
            " WHERE d.status = '处理中'" +
            "   <if test='keyword != null and keyword != \"\"'>" +
            "     AND (pd.name LIKE CONCAT('%', #{keyword}, '%') OR d.demand_no LIKE CONCAT('%', #{keyword}, '%'))" +
            "   </if>" +
            " ORDER BY d.deadline IS NULL, d.deadline ASC, d.id DESC" +
            "</script>")
    IPage<Map<String, Object>> demandHall(IPage<?> page,
                                          @Param("farmerId") Long farmerId,
                                          @Param("keyword") String keyword);

    /** 比价表：同一张需求的全部报价，带农户信用、距离、成交数 */
    @Select("SELECT q.id, q.demand_id AS demandId, q.farmer_id AS farmerId, q.source_id AS sourceId," +
            "       q.price, q.quantity, q.delivery_days AS deliveryDays, q.quality_note AS qualityNote," +
            "       q.remark, q.status, q.create_time AS createTime," +
            "       f.name AS farmerName, f.type AS farmerType, f.credit_score AS creditScore," +
            "       f.distance_km AS distanceKm, f.deal_count AS dealCount, f.good_rate AS goodRate," +
            "       f.main_category AS mainCategory," +
            "       (SELECT COUNT(*) FROM supply_source s WHERE s.farmer_id = q.farmer_id AND s.status = '正常') AS activeSources" +
            "  FROM demand_quote q JOIN farmer f ON f.id = q.farmer_id" +
            " WHERE q.demand_id = #{demandId}" +
            " ORDER BY q.price ASC, f.credit_score DESC")
    List<Map<String, Object>> quotesOfDemand(@Param("demandId") Long demandId);

    /** 农户自己的报价记录 */
    @Select("SELECT q.id, q.demand_id AS demandId, q.price, q.quantity, q.delivery_days AS deliveryDays," +
            "       q.status, q.create_time AS createTime," +
            "       d.demand_no AS demandNo, d.status AS demandStatus, d.expected_price AS expectedPrice," +
            "       d.deadline, pd.name AS productName, pd.unit, c.name AS canteenName" +
            "  FROM demand_quote q" +
            "  JOIN purchase_demand d ON d.id = q.demand_id" +
            "  LEFT JOIN product pd ON pd.id = d.product_id" +
            "  LEFT JOIN canteen c ON c.id = d.canteen_id" +
            " WHERE q.farmer_id = #{farmerId}" +
            " ORDER BY q.id DESC")
    List<Map<String, Object>> myQuotes(@Param("farmerId") Long farmerId);
}
