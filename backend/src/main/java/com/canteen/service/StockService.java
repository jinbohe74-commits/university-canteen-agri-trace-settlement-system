package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.Codes;
import com.canteen.common.UserContext;
import com.canteen.dto.StockOutDTO;
import com.canteen.entity.Product;
import com.canteen.entity.PurchaseDemand;
import com.canteen.entity.Stock;
import com.canteen.entity.StockOut;
import com.canteen.mapper.ProductMapper;
import com.canteen.mapper.PurchaseDemandMapper;
import com.canteen.mapper.StockMapper;
import com.canteen.mapper.StockOutMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 食堂库存、领用与采购建议。
 *
 * <p>采购建议只依据真实流水：日均用量优先用近 28 天<b>领用</b>量，
 * 领用还没积累起来时退回同期<b>到货</b>量推算（并标注来源）；
 * 建议量 = 日均 × 7 + 安全库存 − 在库。安全库存没设置（0）时按 3 天用量自动兜底，
 * 免得每项都要手填；两个来源都没有就明确说「无消耗流水」，不用"假定消耗"凑数字。</p>
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final int COVER_DAYS = 7;
    private static final int HISTORY_DAYS = 28;
    /** 安全库存没设置时，按 3 天用量自动兜底 */
    private static final int AUTO_SAFE_DAYS = 3;
    /** 领用量达到同期到货量的这个比例，才认为领用记录完整 */
    private static final BigDecimal OUT_RECORD_ENOUGH = new BigDecimal("0.6");

    private final StockMapper stockMapper;
    private final StockOutMapper outMapper;
    private final PurchaseDemandMapper demandMapper;
    private final ProductMapper productMapper;
    private final LogService logService;

    /* ---------------------------------------------------------- 库存读写 */

    /** 入库确认时调用：加库存并把单位补齐 */
    public void addStock(Long canteenId, Long productId, BigDecimal qty) {
        if (canteenId == null || productId == null || qty == null || qty.signum() <= 0) {
            return;
        }
        Product p = productMapper.selectById(productId);
        stockMapper.addStock(canteenId, productId, qty, p == null ? "kg" : p.getUnit());
    }

    public Map<String, Object> overview() {
        Long canteenId = UserContext.canteenId();
        List<Map<String, Object>> rows = stockMapper.listStock(canteenId);
        int lowCount = 0;
        int needCount = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal q = dec(r.get("quantity"));
            BigDecimal safe = dec(r.get("safetyQty"));
            BigDecimal daily = dailyAvgOf(r);
            // 与采购建议同一口径：安全库存为 0 时按 3 天用量自动兜底
            boolean safeAuto = safe == null || safe.signum() <= 0;
            BigDecimal effectiveSafe = safeAuto && daily.signum() > 0
                    ? daily.multiply(BigDecimal.valueOf(AUTO_SAFE_DAYS)).setScale(0, RoundingMode.CEILING)
                    : (safe == null ? BigDecimal.ZERO : safe);
            total = total.add(q == null ? BigDecimal.ZERO : q);
            BigDecimal qty = q == null ? BigDecimal.ZERO : q;
            // low：已跌破安全线；need：撑不到覆盖天数（与采购建议同一判据，两个数不会互相打脸）
            boolean low = effectiveSafe.signum() > 0 && qty.compareTo(effectiveSafe) <= 0;
            boolean need = daily.signum() > 0
                    && qty.compareTo(daily.multiply(BigDecimal.valueOf(COVER_DAYS)).add(effectiveSafe)) < 0;
            if (low) {
                lowCount++;
            }
            if (need) {
                needCount++;
            }
            r.put("low", low);
            r.put("need", need);
            r.put("dailyAvg", daily);
            r.put("dailyBasis", basisOf(r));
            r.put("effectiveSafe", effectiveSafe);
            r.put("safeAuto", safeAuto);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("itemCount", rows.size());
        m.put("lowCount", lowCount);
        m.put("needCount", needCount);
        m.put("totalQuantity", total);
        m.put("recentOut", outMapper.recent(canteenId, 8));
        return m;
    }

    @Transactional
    public void out(StockOutDTO dto) {
        Long canteenId = UserContext.canteenId();
        if (dto.getProductId() == null) {
            throw new BizException(400, "请选择出库品项");
        }
        if (dto.getQuantity() == null || dto.getQuantity().signum() <= 0) {
            throw new BizException(400, "出库数量必须大于 0");
        }
        String reason = dto.getReason() == null || dto.getReason().isBlank() ? "领用" : dto.getReason();
        if (stockMapper.deductStock(canteenId, dto.getProductId(), dto.getQuantity()) == 0) {
            Stock s = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                    .eq(Stock::getCanteenId, canteenId).eq(Stock::getProductId, dto.getProductId()));
            BigDecimal left = s == null || s.getQuantity() == null ? BigDecimal.ZERO : s.getQuantity();
            throw new BizException(409, "库存不足：当前在库 " + left.stripTrailingZeros().toPlainString());
        }
        StockOut o = new StockOut();
        o.setCanteenId(canteenId);
        o.setProductId(dto.getProductId());
        o.setQuantity(dto.getQuantity());
        o.setReason(reason);
        o.setOperator(UserContext.getOrNull() == null ? null : UserContext.getOrNull().getUsername());
        o.setRemark(dto.getRemark());
        o.setCreateTime(LocalDateTime.now());
        outMapper.insert(o);

        Product p = productMapper.selectById(dto.getProductId());
        logService.record("库存管理", reason + "出库 " + (p == null ? "" : p.getName()) + " "
                + dto.getQuantity().stripTrailingZeros().toPlainString() + (p == null ? "" : p.getUnit())
                + (dto.getRemark() == null ? "" : "，" + dto.getRemark()));
    }

    /* ---------------------------------------------------------- 采购建议 */

    public Map<String, Object> suggestions() {
        Long canteenId = UserContext.canteenId();
        List<Map<String, Object>> rows = stockMapper.listStock(canteenId);
        List<Map<String, Object>> items = new ArrayList<>();
        int noData = 0;
        int idle = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            BigDecimal qty = dec(r.get("quantity"));
            BigDecimal safe = dec(r.get("safetyQty"));
            Map<String, Object> item = new LinkedHashMap<>(r);
            BigDecimal daily = dailyAvgOf(r);
            String basis = basisOf(r);
            if (daily.signum() <= 0) {
                // 既没有领用流水、同期也没有到货 → 不给建议，但要说清原因，不要含糊成「无需补货」
                idle++;
                noData++;
                item.put("hasData", false);
                item.put("reason", "idle");
                item.put("suggestQty", BigDecimal.ZERO);
                items.add(item);
                continue;
            }
            // 安全库存没设（0）时按 3 天用量自动兜底，免得每项都要手填
            boolean safeAuto = safe == null || safe.signum() <= 0;
            BigDecimal effectiveSafe = safeAuto
                    ? daily.multiply(BigDecimal.valueOf(AUTO_SAFE_DAYS)).setScale(0, RoundingMode.CEILING)
                    : safe;
            BigDecimal target = daily.multiply(BigDecimal.valueOf(COVER_DAYS)).add(effectiveSafe);
            BigDecimal suggest = target.subtract(qty == null ? BigDecimal.ZERO : qty)
                    .setScale(0, RoundingMode.CEILING);
            // 在库能撑几天：判断「是不是真不缺」的直接依据
            BigDecimal cover = qty == null ? BigDecimal.ZERO
                    : qty.divide(daily, 1, RoundingMode.DOWN);
            item.put("dailyAvg", daily);
            item.put("dailyBasis", basis);
            item.put("coverDays", cover);
            item.put("effectiveSafe", effectiveSafe);
            item.put("safeAuto", safeAuto);
            if (suggest.signum() <= 0) {
                noData++;
                item.put("hasData", false);
                item.put("reason", "enough");
                item.put("suggestQty", BigDecimal.ZERO);
                items.add(item);
                continue;
            }
            BigDecimal price = stockMapper.lastInboundPrice(canteenId, toLong(r.get("productId")));
            if (price == null || price.signum() <= 0) {
                price = BigDecimal.ZERO;
            }
            BigDecimal amount = suggest.multiply(price).setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(amount);
            item.put("hasData", true);
            item.put("price", price);
            item.put("suggestQty", suggest);
            item.put("amount", amount);
            items.add(item);
        }
        // 有建议的排前面，其次是有消耗测算但暂不缺的，最后是没有消耗流水的
        items.sort((a, b) -> {
            int ra = rank(a.get("hasData"), a.get("reason"));
            int rb = rank(b.get("hasData"), b.get("reason"));
            return Integer.compare(ra, rb);
        });

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("suggestCount", items.size() - noData);
        m.put("noDataCount", noData);
        m.put("idleCount", idle);
        m.put("totalAmount", totalAmount);
        m.put("coverDays", COVER_DAYS);
        m.put("historyDays", HISTORY_DAYS);
        m.put("autoSafeDays", AUTO_SAFE_DAYS);
        return m;
    }

    private static int rank(Object hasData, Object reason) {
        if (Boolean.TRUE.equals(hasData)) {
            return 0;
        }
        return "enough".equals(reason) ? 1 : 2;
    }

    /** 把选中品项的建议批量转成采购需求，接着走「农户报价 → 比价 → 定标」 */
    @Transactional
    public List<PurchaseDemand> generateDemands(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw new BizException(400, "请至少选择一个品项");
        }
        Long canteenId = UserContext.canteenId();
        Map<Long, Map<String, Object>> suggestMap = new LinkedHashMap<>();
        for (Map<String, Object> it : castList(suggestions().get("items"))) {
            if (Boolean.TRUE.equals(it.get("hasData"))) {
                suggestMap.put(toLong(it.get("productId")), it);
            }
        }
        List<PurchaseDemand> created = new ArrayList<>();
        for (Long pid : productIds) {
            Map<String, Object> s = suggestMap.get(pid);
            if (s == null) {
                continue;
            }
            PurchaseDemand d = new PurchaseDemand();
            d.setCanteenId(canteenId);
            String xp = "X" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            d.setDemandNo(Codes.next(xp, 3, (int) (demandMapper.selectCount(null) % 800 + 100),
                    code -> demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>()
                            .eq(PurchaseDemand::getDemandNo, code)) > 0));
            d.setProductId(pid);
            d.setQuantity(dec(s.get("suggestQty")));
            d.setExpectedPrice(dec(s.get("price")));
            d.setDeadline(LocalDateTime.now().plusDays(2));
            d.setDeliveryMode("冷链配送");
            d.setSettlementMode("月结");
            d.setQualityReq("按食堂验收标准，需随货提供产地证明与农残检测报告");
            d.setQuoteCount(0);
            d.setStatus("处理中");
            demandMapper.insert(d);
            created.add(d);
        }
        if (created.isEmpty()) {
            throw new BizException("所选品项暂无消耗数据，无法生成采购需求");
        }
        logService.record("库存管理", "按采购建议生成 " + created.size() + " 条采购需求："
                + created.stream().map(PurchaseDemand::getDemandNo).reduce((a, b) -> a + "、" + b).orElse(""));
        return created;
    }

    /** 设置安全库存 */
    @Transactional
    public void setSafety(Long productId, BigDecimal safetyQty) {
        Long canteenId = UserContext.canteenId();
        Stock s = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getCanteenId, canteenId).eq(Stock::getProductId, productId));
        if (s == null) {
            throw new BizException(404, "该品项暂无库存记录");
        }
        s.setSafetyQty(safetyQty == null ? BigDecimal.ZERO : safetyQty.max(BigDecimal.ZERO));
        stockMapper.updateById(s);
    }

    /* ---------------------------------------------------------- 工具 */

    private BigDecimal dailyAvg(BigDecimal monthOut) {
        if (monthOut == null || monthOut.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return monthOut.divide(BigDecimal.valueOf(HISTORY_DAYS), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 日均消耗量。以真实领用流水为准；但领用记录往往比系统晚上线，
     * 若领用量明显低于同期到货量（不足 60%），说明领用还没录全，
     * 这时改用同期到货量推算（稳态食堂的到货量约等于消耗量）。
     * 两个来源都是库里的真实记录，不用任何"假定消耗系数"。
     */
    private BigDecimal dailyAvgOf(Map<String, Object> row) {
        BigDecimal out = dec(row.get("monthOut"));
        BigDecimal in = dec(row.get("monthIn"));
        BigDecimal outQty = out == null ? BigDecimal.ZERO : out;
        BigDecimal inQty = in == null ? BigDecimal.ZERO : in;
        BigDecimal floor = inQty.multiply(OUT_RECORD_ENOUGH).setScale(2, RoundingMode.HALF_UP);
        BigDecimal basis = outQty.compareTo(floor) >= 0 ? outQty : inQty;
        return dailyAvg(basis);
    }

    /** 日均的来源：out=领用流水，in=同期到货推算，null=无依据 */
    private String basisOf(Map<String, Object> row) {
        BigDecimal out = dec(row.get("monthOut"));
        BigDecimal in = dec(row.get("monthIn"));
        BigDecimal outQty = out == null ? BigDecimal.ZERO : out;
        BigDecimal inQty = in == null ? BigDecimal.ZERO : in;
        BigDecimal floor = inQty.multiply(OUT_RECORD_ENOUGH).setScale(2, RoundingMode.HALF_UP);
        if (outQty.compareTo(floor) >= 0) {
            return outQty.signum() > 0 ? "out" : null;
        }
        return inQty.signum() > 0 ? "in" : null;
    }

    private Long toLong(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    private boolean bool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object v) {
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }
}
