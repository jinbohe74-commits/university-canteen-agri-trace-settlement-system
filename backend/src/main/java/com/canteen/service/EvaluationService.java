package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.UserContext;
import com.canteen.dto.EvaluationDTO;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.EvaluationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 采购评价：多维评分、评价标签，结果纳入农户信用
 */
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationMapper evaluationMapper;
    private final OrdersMapper ordersMapper;
    private final InboundRecordMapper inboundMapper;
    private final SettlementMapper settlementMapper;
    private final ProductMapper productMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final CreditService creditService;
    private final OrderService orderService;
    private final LogService logService;

    /** 待评价 + 已评价订单 */
    public Map<String, Object> pending(int page, int size) {
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        LambdaQueryWrapper<Orders> qw = new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(90, 100))
                .orderByDesc(Orders::getUpdateTime);
        if (canteenId != null && !UserContext.isAdmin()) {
            qw.eq(Orders::getCanteenId, canteenId);
        }
        List<Orders> orders = ordersMapper.selectList(qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Orders o : orders) {
            Evaluation e = evaluationMapper.selectOne(new LambdaQueryWrapper<Evaluation>()
                    .eq(Evaluation::getOrderId, o.getId()).last("LIMIT 1"));
            if (e == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", o.getId());
                m.put("orderNo", o.getOrderNo());
                Product p = productMapper.selectById(o.getProductId());
                m.put("productName", p == null ? "-" : p.getName());
                Farmer f = farmerMapper.selectById(o.getFarmerId());
                m.put("farmerName", f == null ? "-" : f.getName());
                m.put("farmerId", o.getFarmerId());
                m.put("quantity", o.getQuantity());
                m.put("amount", o.getTotalAmount());
                InboundRecord ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                        .eq(InboundRecord::getOrderId, o.getId()).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
                m.put("receiveQuantity", ir == null ? o.getQuantity() : ir.getReceiveQuantity());
                Settlement s = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                        .eq(Settlement::getOrderId, o.getId()).last("LIMIT 1"));
                m.put("actualAmount", s == null ? o.getTotalAmount() : s.getActualAmount());
                m.put("unit", p == null ? "kg" : p.getUnit());
                m.put("updateTime", o.getUpdateTime());
                rows.add(m);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        res.put("records", rows.subList(from, to));
        res.put("total", rows.size());
        // 供货农户信用信息（最近一次交易的农户）
        if (!rows.isEmpty()) {
            Long farmerId = (Long) rows.get(0).get("farmerId");
            res.put("farmer", farmerCredit(farmerId));
        } else {
            res.put("farmer", null);
        }
        return res;
    }

    public Map<String, Object> farmerCredit(Long farmerId) {
        Farmer f = farmerMapper.selectById(farmerId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (f == null) {
            return m;
        }
        m.put("id", f.getId());
        m.put("name", f.getName());
        m.put("type", f.getType());
        m.put("creditScore", f.getCreditScore());
        m.put("dealCount", f.getDealCount());
        m.put("goodRate", f.getGoodRate());
        List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getFarmerId, farmerId));
        m.put("evalCount", evals.size());
        Map<String, BigDecimal> dims = new LinkedHashMap<>();
        dims.put("产品品质", avg(evals, Evaluation::getQualityScore, 96));
        dims.put("履约时效", avg(evals, Evaluation::getDeliveryScore, 94));
        dims.put("损耗控制", avg(evals, Evaluation::getFreshScore, 90));
        dims.put("服务配合", avg(evals, Evaluation::getServiceScore, 97));
        m.put("dimensions", dims.entrySet().stream().map(e -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", e.getKey());
            d.put("score", e.getValue());
            return d;
        }).toList());
        return m;
    }

    private BigDecimal avg(List<Evaluation> list, java.util.function.Function<Evaluation, BigDecimal> getter,
                          double defaultValue) {
        if (list.isEmpty()) {
            return BigDecimal.valueOf(defaultValue).setScale(0, RoundingMode.HALF_UP);
        }
        BigDecimal sum = list.stream().map(getter).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long n = list.stream().map(getter).filter(Objects::nonNull).count();
        if (n == 0) {
            return BigDecimal.valueOf(defaultValue);
        }
        // 5 分制 -> 百分制
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(20)).setScale(0, RoundingMode.HALF_UP);
    }

    @Transactional
    public Evaluation submit(EvaluationDTO dto) {
        Orders o = ordersMapper.selectById(dto.getOrderId());
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        if (o.getStatus() < 90) {
            throw new BizException("订单尚未结算，暂不能评价");
        }
        if (evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getOrderId, o.getId())) > 0) {
            throw new BizException("该订单已评价，请勿重复提交");
        }
        Evaluation e = new Evaluation();
        e.setOrderId(o.getId());
        e.setCanteenId(o.getCanteenId());
        e.setFarmerId(o.getFarmerId());
        e.setOverallScore(dto.getOverallScore() == null ? BigDecimal.valueOf(5) : dto.getOverallScore());
        e.setQualityScore(dto.getQualityScore() == null ? e.getOverallScore() : dto.getQualityScore());
        e.setFreshScore(dto.getFreshScore() == null ? e.getOverallScore() : dto.getFreshScore());
        e.setDeliveryScore(dto.getDeliveryScore() == null ? e.getOverallScore() : dto.getDeliveryScore());
        e.setServiceScore(dto.getServiceScore() == null ? e.getOverallScore() : dto.getServiceScore());
        e.setPackageScore(dto.getPackageScore() == null ? e.getOverallScore() : dto.getPackageScore());
        e.setTags(dto.getTags() == null ? null : String.join(",", dto.getTags()));
        e.setContent(dto.getContent());
        e.setCreateTime(LocalDateTime.now());
        evaluationMapper.insert(e);

        // 评价结果纳入信用：5 星 +1，4 星及以下不加分
        if (e.getOverallScore().compareTo(BigDecimal.valueOf(4.5)) >= 0) {
            creditService.change(o.getFarmerId(), o.getId(), BigDecimal.ONE, "采购评价 5 星好评");
        }
        // 更新农户好评率
        Farmer f = farmerMapper.selectById(o.getFarmerId());
        if (f != null) {
            List<Evaluation> all = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                    .eq(Evaluation::getFarmerId, o.getFarmerId()));
            long good = all.stream().filter(x -> x.getOverallScore().compareTo(BigDecimal.valueOf(4)) >= 0).count();
            Farmer upd = new Farmer();
            upd.setId(f.getId());
            upd.setGoodRate(BigDecimal.valueOf(good * 100.0 / all.size()).setScale(1, RoundingMode.HALF_UP));
            farmerMapper.updateById(upd);
        }
        // 订单 90 -> 100 已完成
        Orders fresh = ordersMapper.selectById(o.getId());
        if (fresh.getStatus() == 90) {
            orderService.changeStatus(o.getId(), 100, fresh.getVersion());
        }
        logService.record("采购评价", "提交评价 " + o.getOrderNo() + " 总体 "
                + e.getOverallScore() + " 星");
        return e;
    }

    public PageResult<EvaluationVO> history(int page, int size) {
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        LambdaQueryWrapper<Evaluation> qw = new LambdaQueryWrapper<>();
        if (canteenId != null && !UserContext.isAdmin()) {
            qw.eq(Evaluation::getCanteenId, canteenId);
        }
        qw.orderByDesc(Evaluation::getId);
        IPage<Evaluation> p = evaluationMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, e -> {
            EvaluationVO v = new EvaluationVO();
            org.springframework.beans.BeanUtils.copyProperties(e, v);
            Orders o = ordersMapper.selectById(e.getOrderId());
            if (o != null) {
                v.setOrderNo(o.getOrderNo());
                Product prod = productMapper.selectById(o.getProductId());
                v.setProductName(prod == null ? "-" : prod.getName());
                v.setUnit(prod == null ? "kg" : prod.getUnit());
            }
            Farmer f = farmerMapper.selectById(e.getFarmerId());
            v.setFarmerName(f == null ? "-" : f.getName());
            Canteen c = canteenMapper.selectById(e.getCanteenId());
            v.setCanteenName(c == null ? "-" : c.getName());
            v.setEvaluated(true);
            return v;
        });
    }
}
