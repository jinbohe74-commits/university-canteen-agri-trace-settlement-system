package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.entity.Farmer;
import com.canteen.entity.Orders;
import com.canteen.entity.PriceHistory;
import com.canteen.entity.Product;
import com.canteen.entity.Warning;
import com.canteen.mapper.FarmerMapper;
import com.canteen.mapper.OrdersMapper;
import com.canteen.mapper.PriceHistoryMapper;
import com.canteen.mapper.ProductMapper;
import com.canteen.mapper.WarningMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 阈值驱动的预警巡检：证照到期、订单逾期、价格波动
 * 阈值全部来自 sys_config，管理员调整后即时生效
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarningScheduler {

    private final FarmerMapper farmerMapper;
    private final OrdersMapper ordersMapper;
    private final PriceHistoryMapper priceHistoryMapper;
    private final ProductMapper productMapper;
    private final WarningMapper warningMapper;
    private final WarningService warningService;
    private final ConfigService configService;

    /** 每小时整点巡检一次 */
    @Scheduled(cron = "0 5 * * * ?")
    public void inspect() {
        int certDays = configService.getInt("warn_cert_expire_days", 30);
        int overdueDays = configService.getInt("warn_order_overdue_days", 2);
        double priceRate = configService.getDouble("warn_price_rate", 15);
        int created = 0;
        created += checkCertExpire(certDays);
        created += checkOverdue(overdueDays);
        created += checkPrice(priceRate);
        if (created > 0) {
            log.info("预警巡检完成，新增预警 {} 条（证照阈值 {} 天 / 逾期 {} 天 / 价格 ±{}%）",
                    created, certDays, overdueDays, priceRate);
        }
    }

    /** 证照到期预警 */
    private int checkCertExpire(int days) {
        int n = 0;
        List<Farmer> farmers = farmerMapper.selectList(new LambdaQueryWrapper<Farmer>()
                .eq(Farmer::getStatus, "已认证").isNotNull(Farmer::getCertExpire)
                .le(Farmer::getCertExpire, LocalDate.now().plusDays(days)));
        for (Farmer f : farmers) {
            String content = "营业证照将于 " + f.getCertExpire() + " 到期（剩 "
                    + java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), f.getCertExpire()) + " 天）";
            if (exists("证照预警", f.getName())) {
                continue;
            }
            warningService.create(f.getCertExpire().isBefore(LocalDate.now().plusDays(15)) ? "高" : "中",
                    "证照预警", f.getName(), content);
            n++;
        }
        return n;
    }

    /** 订单逾期预警 */
    private int checkOverdue(int days) {
        int n = 0;
        List<Orders> orders = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(10, 20))
                .lt(Orders::getCreateTime, LocalDateTime.now().minusDays(days)));
        for (Orders o : orders) {
            String target = "订单 " + o.getOrderNo();
            if (exists("逾期预警", target)) {
                continue;
            }
            long hours = java.time.Duration.between(o.getCreateTime(), LocalDateTime.now()).toHours();
            warningService.create(hours > days * 48 ? "高" : "中", "逾期预警", target,
                    "超出约定发货时间 " + (hours - days * 24) + " 小时，已自动催办");
            n++;
        }
        return n;
    }

    /** 价格波动预警 */
    private int checkPrice(double rate) {
        int n = 0;
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "在售"));
        LocalDate today = LocalDate.now();
        for (Product p : products) {
            List<PriceHistory> history = priceHistoryMapper.selectList(new LambdaQueryWrapper<PriceHistory>()
                    .eq(PriceHistory::getProductId, p.getId())
                    .ge(PriceHistory::getStatDate, today.minusDays(7))
                    .orderByDesc(PriceHistory::getStatDate));
            if (history.size() < 2) {
                continue;
            }
            BigDecimal latest = history.get(0).getAvgPrice();
            BigDecimal before = history.get(history.size() - 1).getAvgPrice();
            if (before.signum() == 0) {
                continue;
            }
            BigDecimal change = latest.subtract(before).multiply(BigDecimal.valueOf(100))
                    .divide(before, 1, RoundingMode.HALF_UP);
            if (change.abs().doubleValue() < rate) {
                continue;
            }
            if (exists("价格预警", p.getName())) {
                continue;
            }
            warningService.create(change.abs().doubleValue() > rate * 1.5 ? "中" : "低", "价格预警", p.getName(),
                    "均价 ¥" + latest + " 较周参考 ¥" + before + (change.signum() >= 0 ? " 上涨 " : " 下跌 ")
                            + change.abs() + "%（超 ±" + rate + "%）");
            n++;
        }
        return n;
    }

    private boolean exists(String type, String target) {
        return warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getWarningType, type)
                .eq(Warning::getTarget, target)
                .in(Warning::getStatus, List.of("待处理", "待确认"))) > 0;
    }
}
