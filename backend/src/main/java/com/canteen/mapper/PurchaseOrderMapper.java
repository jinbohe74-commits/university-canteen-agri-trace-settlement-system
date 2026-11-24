package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.canteen.entity.PurchaseOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    /**
     * 采购单列表。状态由子订单实时推导，不依赖 purchase_order.status 的存储值，
     * 避免「子单已入库、采购单还显示待接单」这类不一致。
     */
    @Select("<script>" +
            "SELECT * FROM (" +
            "  SELECT p.id, p.po_no AS poNo, p.canteen_id AS canteenId, p.purchaser_id AS purchaserId," +
            "         p.demand_id AS demandId, p.item_count AS itemCount, p.total_amount AS totalAmount," +
            "         p.expected_delivery AS expectedDelivery, p.remark, p.create_time AS createTime," +
            "         c.name AS canteenName," +
            "         (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id) AS orderCount," +
            "         (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id AND o.status >= 100) AS doneCount," +
            "         (SELECT GROUP_CONCAT(CONCAT(pd.name, ' ', CAST(o.quantity AS DECIMAL(10,0)), pd.unit)" +
            "                             ORDER BY pd.name SEPARATOR '、')" +
            "            FROM orders o JOIN product pd ON pd.id = o.product_id WHERE o.po_id = p.id) AS itemsText," +
            "         CASE" +
            "           WHEN (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id AND o.status = 99)" +
            "                = (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id) THEN '已取消'" +
            "           WHEN (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id AND o.status >= 100)" +
            "                = (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id) THEN '已完成'" +
            "           WHEN (SELECT COUNT(*) FROM orders o WHERE o.po_id = p.id AND o.status > 10) > 0 THEN '进行中'" +
            "           ELSE '待接单' END AS status" +
            "    FROM purchase_order p LEFT JOIN canteen c ON c.id = p.canteen_id" +
            "   WHERE p.canteen_id = #{canteenId}" +
            "     <if test='keyword != null and keyword != \"\"'>" +
            "       AND (p.po_no LIKE CONCAT('%', #{keyword}, '%')" +
            "            OR EXISTS (SELECT 1 FROM orders o JOIN product pd ON pd.id = o.product_id" +
            "                        WHERE o.po_id = p.id AND pd.name LIKE CONCAT('%', #{keyword}, '%')))" +
            "     </if>" +
            ") t" +
            " WHERE 1 = 1" +
            " <if test='status != null and status != \"\"'> AND t.status = #{status} </if>" +
            " ORDER BY t.id DESC" +
            "</script>")
    IPage<Map<String, Object>> pagePo(IPage<?> page,
                                      @Param("canteenId") Long canteenId,
                                      @Param("status") String status,
                                      @Param("keyword") String keyword);

    /** 采购单下的履约子单（含产品名、农户名、实时状态） */
    @Select("SELECT o.id, o.order_no AS orderNo, o.status, o.quantity, o.unit_price AS unitPrice," +
            "       o.total_amount AS totalAmount, o.batch_no AS batchNo, o.expected_delivery AS expectedDelivery," +
            "       pd.name AS productName, pd.unit," +
            "       f.name AS farmerName, f.credit_score AS creditScore" +
            "  FROM orders o" +
            "  LEFT JOIN product pd ON pd.id = o.product_id" +
            "  LEFT JOIN farmer f ON f.id = o.farmer_id" +
            " WHERE o.po_id = #{poId} ORDER BY o.id")
    List<Map<String, Object>> itemsOfPo(@Param("poId") Long poId);
}
