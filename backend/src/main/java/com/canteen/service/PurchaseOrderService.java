package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.Codes;
import com.canteen.common.UserContext;
import com.canteen.dto.PurchaseOrderDTO;
import com.canteen.entity.Orders;
import com.canteen.entity.PurchaseDemand;
import com.canteen.entity.PurchaseOrder;
import com.canteen.entity.SupplySource;
import com.canteen.mapper.PurchaseDemandMapper;
import com.canteen.mapper.PurchaseOrderMapper;
import com.canteen.mapper.SupplySourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 采购单：采购员一次提交多个品项。
 *
 * <p>数据模型上采用「采购单 → 履约子单」两层，而不是把 orders 改成多明细：
 * 质检、配送、入库、损耗、结算都建立在「一张订单 = 一个品项」的前提上，
 * 拆开这两层可以让采购体验是「一单多品项」，同时不破坏下游状态机与核算口径。</p>
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderMapper poMapper;
    private final PurchaseDemandMapper demandMapper;
    private final SupplySourceMapper sourceMapper;
    private final com.canteen.mapper.ProductMapper productMapper;
    private final OrderService orderService;
    private final LogService logService;

    @Transactional
    public PurchaseOrder create(PurchaseOrderDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BizException(400, "请至少选择一个货源");
        }
        Long canteenId = UserContext.canteenId();

        // 同一货源被加两次自动合并数量，避免重复扣库存与重复建单
        Map<Long, BigDecimal> lines = new LinkedHashMap<>();
        for (PurchaseOrderDTO.Item it : dto.getItems()) {
            if (it.getSourceId() == null || it.getQuantity() == null) {
                throw new BizException(400, "货源与数量不能为空");
            }
            if (it.getQuantity().signum() <= 0) {
                throw new BizException(400, "采购数量必须大于 0");
            }
            lines.merge(it.getSourceId(), it.getQuantity(), BigDecimal::add);
        }

        PurchaseDemand demand = null;
        if (dto.getDemandId() != null) {
            demand = demandMapper.selectById(dto.getDemandId());
            if (demand == null) {
                throw new BizException(404, "采购需求不存在");
            }
            if (!Objects.equals(demand.getCanteenId(), canteenId)) {
                throw new BizException(403, "只能为本食堂的采购需求下单");
            }
            if ("已撤回".equals(demand.getStatus())) {
                throw new BizException("该需求已撤回，无法下单");
            }
        }

        // 先整体校验再扣库存：一次提交几十个品项时，不能扣了一半才报错
        Map<Long, SupplySource> sources = new LinkedHashMap<>();
        for (Map.Entry<Long, BigDecimal> e : lines.entrySet()) {
            SupplySource s = sourceMapper.selectById(e.getKey());
            if (s == null) {
                throw new BizException(404, "货源不存在（ID " + e.getKey() + "）");
            }
            if (!"正常".equals(s.getStatus())) {
                throw new BizException("「" + productName(s) + "」当前不可下单（" + s.getStatus() + "）");
            }
            if (s.getPrice() == null || s.getPrice().signum() <= 0) {
                throw new BizException(400, "「" + productName(s) + "」报价异常，无法下单");
            }
            if (s.getQuantity() == null || e.getValue().compareTo(s.getQuantity()) > 0) {
                throw new BizException("「" + productName(s) + "」采购量超过可供数量（剩余 "
                        + (s.getQuantity() == null ? BigDecimal.ZERO : s.getQuantity()) + s.getUnit() + "）");
            }
            sources.put(e.getKey(), s);
        }

        PurchaseOrder po = new PurchaseOrder();
        String datePart = "CG" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        po.setPoNo(Codes.next(datePart, 4,
                (int) (poMapper.selectCount(null) % 9000 + 1),
                code -> poMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getPoNo, code)) > 0));
        po.setCanteenId(canteenId);
        po.setPurchaserId(UserContext.getOrNull() == null ? null : UserContext.userId());
        po.setDemandId(dto.getDemandId());
        po.setItemCount(0);
        po.setTotalAmount(BigDecimal.ZERO);
        po.setExpectedDelivery(dto.getExpectedDelivery() == null
                ? LocalDateTime.now().plusDays(1) : dto.getExpectedDelivery());
        po.setStatus("待接单");
        po.setRemark(dto.getRemark());
        poMapper.insert(po);

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Map.Entry<Long, BigDecimal> e : lines.entrySet()) {
            Orders o = orderService.create(dto.getDemandId(), e.getKey(), e.getValue(),
                    po.getExpectedDelivery(), po.getId());
            total = total.add(o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount());
            count++;
        }

        po.setItemCount(count);
        po.setTotalAmount(total);
        poMapper.updateById(po);

        logService.record("采购协同", "生成采购单 " + po.getPoNo() + "，共 " + count + " 个品项，合计 ¥" + total
                + (demand == null ? "" : "，关联需求 " + demand.getDemandNo()));
        return po;
    }

    public IPage<Map<String, Object>> page(int page, int size, String status, String keyword) {
        Page<Map<String, Object>> p = new Page<>(page, size);
        return poMapper.pagePo(p, UserContext.canteenId(), status,
                keyword == null || keyword.isBlank() ? null : keyword.trim());
    }

    public Map<String, Object> detail(Long id) {
        PurchaseOrder po = poMapper.selectById(id);
        if (po == null) {
            throw new BizException(404, "采购单不存在");
        }
        if (!Objects.equals(po.getCanteenId(), UserContext.canteenId()) && !UserContext.isAdmin()) {
            throw new BizException(403, "无权查看其他食堂的采购单");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("po", po);
        m.put("items", poMapper.itemsOfPo(id));
        return m;
    }

    /** 采购单概览统计（给页面顶部用） */
    public Map<String, Object> stats() {
        Long canteenId = UserContext.canteenId();
        Map<String, Object> m = new LinkedHashMap<>();
        List<PurchaseOrder> all = poMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getCanteenId, canteenId)
                .ge(PurchaseOrder::getCreateTime, LocalDateTime.now().minusDays(30)));
        int multi = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (PurchaseOrder p : all) {
            if (p.getItemCount() != null && p.getItemCount() > 1) {
                multi++;
            }
            if (p.getTotalAmount() != null) {
                sum = sum.add(p.getTotalAmount());
            }
        }
        m.put("last30d", all.size());
        m.put("multiItem", multi);
        m.put("totalAmount", sum);
        return m;
    }

    private String productName(SupplySource s) {
        if (s.getProductId() == null) {
            return "货源#" + s.getId();
        }
        com.canteen.entity.Product p = productMapper.selectById(s.getProductId());
        return p == null ? "货源#" + s.getId() : p.getName();
    }
}
