package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.SysNotification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SysNotificationMapper extends BaseMapper<SysNotification> {

    /** 只查属于该用户的通知 —— 定向过滤下推到 SQL，不在应用层兜底 */
    @Select("SELECT * FROM sys_notification WHERE recipient_id = #{userId} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<SysNotification> recent(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM sys_notification WHERE recipient_id = #{userId} AND is_read = 0")
    Long unreadCount(@Param("userId") Long userId);

    /** 带 recipient_id 条件，防止把别人的通知标成已读 */
    @Update("UPDATE sys_notification SET is_read = 1, read_time = NOW() " +
            "WHERE id = #{id} AND recipient_id = #{userId} AND is_read = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE sys_notification SET is_read = 1, read_time = NOW() " +
            "WHERE recipient_id = #{userId} AND is_read = 0")
    int markAllRead(@Param("userId") Long userId);

    /** 只能删自己的、且必须已读：未读通知是待处理线索，不允许删掉当作没发生 */
    @Delete("DELETE FROM sys_notification WHERE id = #{id} AND recipient_id = #{userId} AND is_read = 1")
    int deleteRead(@Param("id") Long id, @Param("userId") Long userId);

    /** 一键清空已读；未读一条都不动 */
    @Delete("DELETE FROM sys_notification WHERE recipient_id = #{userId} AND is_read = 1")
    int deleteAllRead(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM sys_notification WHERE recipient_id = #{userId}")
    Long total(@Param("userId") Long userId);

    /** 按订单归属解析真正的责任人，而不是按角色群发给几十个人 */
    @Select("SELECT u.id FROM sys_user u JOIN farmer f ON f.user_id = u.id " +
            "WHERE f.id = #{farmerId} AND u.status = 1")
    List<Long> userIdsOfFarmer(@Param("farmerId") Long farmerId);

    @Select("SELECT u.id FROM sys_user u JOIN canteen c ON c.user_id = u.id " +
            "WHERE c.id = #{canteenId} AND u.status = 1")
    List<Long> userIdsOfCanteen(@Param("canteenId") Long canteenId);

    /** 该订单实际的质检经办人，优先于「发给所有质检员」 */
    // MySQL 8 ONLY_FULL_GROUP_BY：DISTINCT 不能搭配 ORDER BY 未投影列(3065)，
    // 用 GROUP BY inspector_id + MAX(id) 排序，语义等价于「最近一次检测的人优先」。
    @Select("SELECT inspector_id FROM quality_report " +
            "WHERE order_id = #{orderId} AND inspector_id IS NOT NULL " +
            "GROUP BY inspector_id ORDER BY MAX(id) DESC")
    List<Long> userIdsOfOrderInspector(@Param("orderId") Long orderId);

    /** 兜底：无档案绑定的经办角色（如结算审核管理员），只发给该角色启用账号 */
    @Select("SELECT id FROM sys_user WHERE role_type = #{role} AND status = 1")
    List<Long> userIdsOfRole(@Param("role") String role);

    /** 应用启动时幂等建表 */
    @Update("CREATE TABLE IF NOT EXISTS sys_notification (" +
            "id BIGINT NOT NULL AUTO_INCREMENT, " +
            "biz_type VARCHAR(40) NOT NULL, biz_no VARCHAR(60) NULL, order_id BIGINT NULL, " +
            "sender_id BIGINT NULL, sender_name VARCHAR(60) NULL, sender_role VARCHAR(30) NULL, " +
            "recipient_id BIGINT NOT NULL, recipient_name VARCHAR(60) NULL, recipient_role VARCHAR(30) NULL, " +
            "title VARCHAR(120) NOT NULL, content VARCHAR(400) NULL, route VARCHAR(160) NULL, " +
            "is_read TINYINT NOT NULL DEFAULT 0, read_time DATETIME NULL, " +
            "create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
            "PRIMARY KEY (id), KEY idx_recipient (recipient_id, is_read, id), " +
            "KEY idx_biz (biz_type, biz_no), KEY idx_order (order_id)) " +
            "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTableIfAbsent();
}
