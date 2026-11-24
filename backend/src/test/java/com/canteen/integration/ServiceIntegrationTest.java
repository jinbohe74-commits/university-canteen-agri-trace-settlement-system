package com.canteen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.Orders;
import com.canteen.entity.PurchaseDemand;
import com.canteen.entity.Settlement;
import com.canteen.mapper.OrdersMapper;
import com.canteen.mapper.PurchaseDemandMapper;
import com.canteen.mapper.SettlementMapper;
import com.canteen.service.ConfigService;
import com.canteen.service.MatchService;
import com.canteen.service.OrderService;
import com.canteen.vo.MatchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 集成测试：真实 Spring 容器 + 真实 MySQL（canteen_trace）。
 * 类级 @Transactional 保证所有写操作在用例结束后回滚，不污染演示数据。
 */
@SpringBootTest
@Transactional
class ServiceIntegrationTest {

    @Autowired private OrdersMapper ordersMapper;
    @Autowired private SettlementMapper settlementMapper;
    @Autowired private PurchaseDemandMapper demandMapper;
    @Autowired private OrderService orderService;
    @Autowired private MatchService matchService;
    @Autowired private ConfigService configService;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void asAdmin() {
        LoginUser lu = new LoginUser();
        lu.setUserId(1L);
        lu.setRealName("集成测试");
        lu.setRole("ADMIN");
        UserContext.set(lu);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private Orders pickStatus(int status) {
        return ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, status).last("LIMIT 1"));
    }

    @Test
    @DisplayName("DB 全量结算单满足「实际 = 订单金额 - 损耗扣款 - 质检扣款」")
    void settlementAmountFormulaHoldsInDatabase() {
        List<Settlement> all = settlementMapper.selectList(null);
        assertFalse(all.isEmpty(), "演示库应有结算单");
        List<String> bad = new ArrayList<>();
        for (Settlement s : all) {
            BigDecimal expected = nz(s.getTotalAmount()).subtract(nz(s.getLossDeduction()))
                    .subtract(nz(s.getDeduction())).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal actual = nz(s.getActualAmount()).setScale(2, RoundingMode.HALF_UP);
            if (expected.compareTo(actual) != 0) {
                bad.add(s.getSettlementNo() + " expected=" + expected + " actual=" + actual);
            }
        }
        assertTrue(bad.isEmpty(), "共 " + bad.size() + " 条金额不一致，示例：" + bad.subList(0, Math.min(5, bad.size())));
    }

    @Test
    @DisplayName("状态机拒绝非法跳转 10→70")
    void stateMachineRejectsIllegalTransition() {
        Orders o = pickStatus(10);
        assumeTrue(o != null, "演示库需存在待接单订单");
        BizException ex = assertThrows(BizException.class,
                () -> orderService.changeStatus(o.getId(), 70, null));
        assertTrue(ex.getMessage().contains("不允许变更"));
    }

    @Test
    @DisplayName("乐观锁：陈旧 version 返回业务码 409")
    void staleVersionReturnsConflict() {
        Orders o = pickStatus(10);
        assumeTrue(o != null, "演示库需存在待接单订单");
        BizException ex = assertThrows(BizException.class,
                () -> orderService.changeStatus(o.getId(), 20, 999999));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("数据权限：农户读取他人订单详情返回 403")
    void dataScopeBlocksCrossFarmerRead() {
        List<Orders> list = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .isNotNull(Orders::getFarmerId).last("LIMIT 200"));
        assumeTrue(list.size() > 1);
        Long own = list.get(0).getFarmerId();
        Orders other = list.stream().filter(x -> !Objects.equals(x.getFarmerId(), own))
                .findFirst().orElse(null);
        assumeTrue(other != null, "演示库需存在多个农户的订单");

        LoginUser lu = new LoginUser();
        lu.setUserId(99L);
        lu.setRole("FARMER");
        lu.setFarmerId(own);
        UserContext.set(lu);
        BizException ex = assertThrows(BizException.class, () -> orderService.getById(other.getId()));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("五因子匹配：权重和=1、Top5、得分在 0~100")
    void matchReturnsTop5WithConsistentScore() {
        PurchaseDemand d = demandMapper.selectOne(new LambdaQueryWrapper<PurchaseDemand>().last("LIMIT 1"));
        assumeTrue(d != null);
        BigDecimal sum = configService.matchWeights().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, BigDecimal.ONE.compareTo(sum), "sys_config 权重和应为 1");

        Map<String, Object> res = matchService.match(d.getId());
        @SuppressWarnings("unchecked")
        List<MatchVO> results = (List<MatchVO>) res.get("results");
        assertNotNull(results);
        assertFalse(results.isEmpty(), "演示库应存在可匹配货源");
        assertTrue(results.size() <= 5, "应截断为 Top5");
        for (MatchVO v : results) {
            assertTrue(v.getScore().doubleValue() >= 0 && v.getScore().doubleValue() <= 100,
                    "得分越界：" + v.getScore());
        }
    }

    @Test
    @DisplayName("系统参数修改后即时生效（权重和必须为 1，事务回滚不留脏配置）")
    void configChangeTakesEffectImmediately() {
        configService.update(Map.of(
                "match_weight_category", "0.31",
                "match_weight_price", "0.24",
                "match_weight_quantity", "0.15",
                "match_weight_credit", "0.20",
                "match_weight_distance", "0.10"));
        assertEquals(0, new BigDecimal("0.31").compareTo(configService.matchWeights().get("category")));
    }

    @Test
    @DisplayName("[修复验证] 已移除全局逻辑删除配置，与无 deleted 列的表结构一致")
    void logicDeleteConfigMismatchWithSchema() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND column_name = 'deleted'");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "逻辑删除配置与表结构脱节：所有删除实际是物理删除");
        }
    }

    @Test
    @DisplayName("订单号在 DB 层无重复（唯一约束生效）")
    void orderNosAreUnique() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM "
                    + "(SELECT order_no FROM orders GROUP BY order_no HAVING COUNT(*) > 1) t");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }
}
