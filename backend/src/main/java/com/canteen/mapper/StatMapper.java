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

    /** 近 N 天价格行情 */
    @Select("SELECT p.name AS product, h.stat_date AS statDate, h.avg_price AS avgPrice " +
            "FROM price_history h JOIN product p ON p.id = h.product_id " +
            "WHERE h.product_id IN (#{p1}, #{p2}) AND h.stat_date >= #{from} ORDER BY h.stat_date")
    List<Map<String, Object>> priceTrend(@Param("p1") Long p1, @Param("p2") Long p2,
                                         @Param("from") String from);

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
}


