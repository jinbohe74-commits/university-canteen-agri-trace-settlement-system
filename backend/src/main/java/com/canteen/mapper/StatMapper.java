package com.canteen.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 统计类查询（看板、预警、结算、日志）
 */
public interface StatMapper {

    @Select("SELECT COUNT(*) FROM orders")
    long totalOrders();

    @Select("SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE status NOT IN (41,99)")
    BigDecimal totalAmount();

    @Select("SELECT COALESCE(ROUND(SUM(result='合格')/NULLIF(COUNT(*),0)*100,1),0) FROM quality_report")
    BigDecimal qualityPassRate();

    @Select("SELECT COUNT(*) FROM farmer WHERE status = '已认证'")
    long certifiedFarmers();

    @Select("SELECT COUNT(*) FROM farmer WHERE DATE_FORMAT(join_time,'%Y-%m') = DATE_FORMAT(NOW(),'%Y-%m')")
    long newCertifiedFarmers();

    @Select("SELECT COUNT(*) FROM canteen WHERE status = '正常'")
    long activeCanteens();

    @Select("SELECT COALESCE(SUM(scale),0) FROM canteen WHERE status = '正常'")
    long dailyMeals();

    @Select("SELECT COUNT(*) FROM orders WHERE DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')")
    long monthOrders();

    @Select("SELECT COUNT(*) FROM orders WHERE DATE(create_time)=CURDATE()")
    long todayOrders();

    @Select("SELECT COALESCE(SUM(total_amount),0) FROM orders WHERE DATE(create_time)=CURDATE()")
    BigDecimal todayAmount();

    @Select("SELECT COUNT(*) FROM orders WHERE status = #{status}")
    long countByStatus(@Param("status") int status);

    /** 品类分布（按产品大类统计有效订单） */
    @Select("SELECT pc.category_name AS name, COUNT(o.id) AS cnt, COALESCE(SUM(o.total_amount),0) AS amount " +
            "FROM orders o JOIN product p ON p.id = o.product_id " +
            "JOIN product_category c ON c.id = p.category_id " +
            "JOIN product_category pc ON pc.id = c.parent_id " +
            "WHERE o.status NOT IN (41,99) GROUP BY pc.category_name ORDER BY cnt DESC")
    List<Map<String, Object>> categoryDistribution();

    /**
     * 可选的行情商品清单：用<b>真实订单成交价</b>统计，而不是只有 9 个品项、
     * 停在半个月前的 price_history 演示表。这样行情图能覆盖所有实际买过的品项。
     */
    @Select("SELECT p.id AS productId, p.name AS name, p.unit, " +
            "       COALESCE(pc.category_name, '其他') AS categoryName, " +
            "       COUNT(*) AS orderCount, " +
            "       ROUND(SUM(o.total_amount) / NULLIF(SUM(o.quantity), 0), 2) AS avgPrice, " +
            "       MAX(DATE(o.create_time)) AS lastDate, " +
            "       ROUND(MIN(o.unit_price), 2) AS minPrice, " +
            "       ROUND(MAX(o.unit_price), 2) AS maxPrice " +
            "FROM orders o JOIN product p ON p.id = o.product_id " +
            "LEFT JOIN product_category c ON c.id = p.category_id " +
            "LEFT JOIN product_category pc ON pc.id = c.parent_id " +
            "WHERE o.status NOT IN (41, 99) AND o.quantity > 0 " +
            "GROUP BY p.id, p.name, p.unit, pc.category_name " +
            "ORDER BY orderCount DESC, p.id")
    List<Map<String, Object>> priceProducts();

    /** 选定商品在近 N 天的每日成交均价（真实成交，非行情快照） */
    @Select("<script>SELECT o.product_id AS productId, p.name AS product, " +
            "DATE(o.create_time) AS statDate, " +
            "ROUND(SUM(o.total_amount) / NULLIF(SUM(o.quantity), 0), 2) AS avgPrice, " +
            "ROUND(SUM(o.quantity), 1) AS qty, COUNT(*) AS cnt " +
            "FROM orders o JOIN product p ON p.id = o.product_id " +
            "WHERE o.status NOT IN (41, 99) AND o.quantity > 0 " +
            "AND o.create_time &gt;= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "AND o.product_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY o.product_id, p.name, DATE(o.create_time) " +
            "ORDER BY statDate, o.product_id</script>")
    List<Map<String, Object>> priceTrendDaily(@Param("ids") List<Long> ids, @Param("days") int days);

    /** 长窗口（>30 天）按周聚合：每天只有一两单时折线会全是锯齿，按周才看得出趋势 */
    @Select("<script>SELECT o.product_id AS productId, p.name AS product, " +
            "DATE(DATE_SUB(o.create_time, INTERVAL WEEKDAY(o.create_time) DAY)) AS statDate, " +
            "ROUND(SUM(o.total_amount) / NULLIF(SUM(o.quantity), 0), 2) AS avgPrice, " +
            "ROUND(SUM(o.quantity), 1) AS qty, COUNT(*) AS cnt " +
            "FROM orders o JOIN product p ON p.id = o.product_id " +
            "WHERE o.status NOT IN (41, 99) AND o.quantity > 0 " +
            "AND o.create_time &gt;= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "AND o.product_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY o.product_id, p.name, " +
            "DATE(DATE_SUB(o.create_time, INTERVAL WEEKDAY(o.create_time) DAY)) " +
            "ORDER BY statDate, o.product_id</script>")
    List<Map<String, Object>> priceTrendWeekly(@Param("ids") List<Long> ids, @Param("days") int days);

    /** 有价格历史的商品里最新的两个（同名商品可能有多条档案，只取真正有数据的） */
    @Select("SELECT h.product_id AS productId, p.name AS name, MAX(h.stat_date) AS lastDate " +
            "FROM price_history h JOIN product p ON p.id = h.product_id " +
            "GROUP BY h.product_id, p.name ORDER BY lastDate DESC, h.product_id LIMIT 2")
    List<Map<String, Object>> topPriceProducts();

    @Select("SELECT COUNT(*) FROM settlement WHERE status = '待审核'")
    long pendingSettlements();

    @Select("SELECT COALESCE(SUM(loss_deduction),0) FROM settlement WHERE status = '待审核'")
    BigDecimal pendingLossDeduction();

    @Select("SELECT COUNT(*) FROM orders WHERE status = 10 AND create_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long overdueOrders(@Param("days") int days);

    @Select("SELECT COUNT(*) FROM warning WHERE status = '待处理' AND warning_type = #{type}")
    long pendingWarningsByType(@Param("type") String type);

    @Select("SELECT COUNT(*) FROM warning WHERE status = '待处理' AND warning_type = '质检预警' AND content LIKE '%不合格%'")
    long pendingUnqualifiedWarnings();

    @Select("SELECT COUNT(*) FROM farmer WHERE status = '已认证' AND cert_expire <= DATE_ADD(CURDATE(), INTERVAL #{days} DAY)")
    long expiringCerts(@Param("days") int days);

    @Select("SELECT name, credit_score AS creditScore, type FROM farmer " +
            "WHERE status = '已认证' ORDER BY credit_score DESC LIMIT 5")
    List<Map<String, Object>> creditTop5();

    /** 各食堂采购占比 */
    @Select("SELECT c.name AS name, COUNT(o.id) AS cnt, COALESCE(SUM(o.total_amount),0) AS amount " +
            "FROM orders o JOIN canteen c ON c.id = o.canteen_id " +
            "WHERE o.status NOT IN (41,99) GROUP BY c.name ORDER BY amount DESC")
    List<Map<String, Object>> canteenShare();

    /** 本月结算概况 */
    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount),0) AS total, " +
            "COALESCE(SUM(loss_deduction),0) AS loss, COALESCE(SUM(actual_amount),0) AS actual " +
            "FROM settlement WHERE DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')")
    Map<String, Object> monthSettlement();

    @Select("SELECT status, COUNT(*) AS cnt FROM settlement GROUP BY status")
    List<Map<String, Object>> settlementStatusCount();

    @Select("SELECT warning_type AS type, COUNT(*) AS cnt FROM warning GROUP BY warning_type")
    List<Map<String, Object>> warningTypeCount();

    @Select("SELECT risk_level AS level, COUNT(*) AS cnt FROM warning GROUP BY risk_level")
    List<Map<String, Object>> warningLevelCount();

    /** 近 30 天预警趋势 */
    @Select("SELECT DATE_FORMAT(trigger_time,'%m-%d') AS day, COUNT(*) AS cnt FROM warning " +
            "WHERE trigger_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) GROUP BY day ORDER BY day")
    List<Map<String, Object>> warningTrend();

    @Select("SELECT COUNT(*) FROM operation_log WHERE DATE(create_time)=CURDATE()")
    long todayLogs();

    @Select("SELECT COUNT(DISTINCT operator) FROM operation_log WHERE DATE(create_time)=CURDATE()")
    long todayActiveUsers();

    @Select("SELECT COUNT(*) FROM operation_log WHERE result='失败' AND DATE(create_time)=CURDATE()")
    long todayFailedLogs();

    @Select("SELECT COUNT(*) FROM operation_log")
    long totalLogs();

    @Select("SELECT COUNT(*) FROM operation_log WHERE result='失败'")
    long failedLogs();

    @Select("SELECT module, COUNT(*) AS cnt FROM operation_log GROUP BY module ORDER BY cnt DESC")
    List<Map<String, Object>> logModuleCount();

    /** 农户近 6 个月收入 */
    @Select("SELECT DATE_FORMAT(s.create_time,'%Y-%m') AS m, COALESCE(SUM(s.actual_amount),0) AS amount " +
            "FROM settlement s WHERE s.farmer_id = #{farmerId} AND s.status = '已结算' " +
            "AND s.create_time >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH) GROUP BY m ORDER BY m")
    List<Map<String, Object>> farmerIncomeTrend(@Param("farmerId") Long farmerId);

    @Select("SELECT COUNT(*) FROM settlement WHERE farmer_id = #{farmerId} AND status = '已结算'")
    long farmerSettledCount(@Param("farmerId") Long farmerId);

    @Select("SELECT COALESCE(SUM(actual_amount),0) FROM settlement WHERE farmer_id = #{farmerId} " +
            "AND status = '已结算' AND DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')")
    BigDecimal farmerMonthIncome(@Param("farmerId") Long farmerId);

    @Select("SELECT COALESCE(SUM(actual_amount),0) FROM settlement WHERE farmer_id = #{farmerId} AND status = '已结算'")
    BigDecimal farmerTotalIncome(@Param("farmerId") Long farmerId);

    /** 质检员：近 7 日合格率 */
    @Select("SELECT DATE_FORMAT(inspect_time,'%m-%d') AS day, COUNT(*) AS cnt, " +
            "COALESCE(SUM(result='合格'),0) AS ok FROM quality_report " +
            "WHERE inspect_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY day ORDER BY day")
    List<Map<String, Object>> inspectorPassTrend();

    @Select("SELECT COUNT(*) FROM quality_report WHERE DATE(inspect_time)=CURDATE()")
    long todayInspected();

    @Select("SELECT COUNT(*) FROM quality_report WHERE DATE(inspect_time)=CURDATE() AND result='合格'")
    long todayQualified();

    @Select("SELECT COUNT(*) FROM quality_report WHERE result='不合格'")
    long unqualifiedTotal();

    @Select("SELECT COALESCE(SUM(quantity),0) FROM orders WHERE status >= 40 AND status NOT IN (41,99) " +
            "AND DATE(create_time)=CURDATE()")
    BigDecimal todayInspectQuantity();

    /** 待质检订单（质检员任务） */
    @Select("SELECT o.id, o.order_no, o.batch_no, o.status, o.quantity, o.create_time, o.product_id, " +
            "o.farmer_id, o.canteen_id FROM orders o WHERE o.status = 30 ORDER BY o.create_time ASC LIMIT 20")
    List<Map<String, Object>> pendingInspectOrders();

    /**
     * 质检报告逐批明细（质检员看板的「今日已检 / 不合格」必须有真实行，
     * 不能拿 status=30 的待检列表冒充）。
     * <p>todayOnly / onlyUnqualified 由调用方传 0/1，避免 MyBatis 动态 SQL 的额外映射。</p>
     */
    @Select("SELECT q.id, q.batch_no AS batchNo, q.result, q.sample_qty AS sampleQty, " +
            "       q.inspect_time AS inspectTime, q.inspector, q.remark, " +
            "       o.order_no AS orderNo, o.farmer_id AS farmerId, o.canteen_id AS canteenId, " +
            "       o.product_id AS productId, o.quantity, o.total_amount AS totalAmount, " +
            "       p.name AS productName, c.category_name AS categoryName, " +
            "       f.name AS farmerName, ct.name AS canteenName " +
            "FROM quality_report q " +
            "LEFT JOIN orders o ON o.id = q.order_id " +
            "LEFT JOIN product p ON p.id = o.product_id " +
            "LEFT JOIN product_category c ON c.id = p.category_id " +
            "LEFT JOIN farmer f ON f.id = o.farmer_id " +
            "LEFT JOIN canteen ct ON ct.id = o.canteen_id " +
            "WHERE (#{todayOnly} = 0 OR DATE(q.inspect_time) = CURDATE()) " +
            "  AND (#{onlyUnqualified} = 0 OR q.result = '不合格') " +
            "ORDER BY q.inspect_time DESC LIMIT #{limit}")
    List<Map<String, Object>> qualityReportList(@Param("todayOnly") int todayOnly,
                                                @Param("onlyUnqualified") int onlyUnqualified,
                                                @Param("limit") int limit);
}


