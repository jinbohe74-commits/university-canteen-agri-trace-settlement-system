package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.UserContext;
import com.canteen.dto.PurchaseOrderDTO;
import com.canteen.dto.QuoteDTO;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 需求 → 报价 → 中标转单。
 *
 * <p>补上原来缺失的一环：采购需求不再只停留在浏览，农户可以真实报价，
 * 采购员横向比价后选定中标，直接生成采购单并回填 demand_id，
 * quote_count 一律由 demand_quote 的真实行数刷新，杜绝"好看的假报价数"。</p>
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final DemandQuoteMapper quoteMapper;
    private final PurchaseDemandMapper demandMapper;
    private final FarmerMapper farmerMapper;
    private final SupplySourceMapper sourceMapper;
    private final ProductMapper productMapper;
    private final PurchaseOrderService purchaseOrderService;
    private final LogService logService;

    /* ------------------------------------------------------------ 农户端 */

    public IPage<Map<String, Object>> demandHall(int page, int size, String keyword) {
        Page<Map<String, Object>> p = new Page<>(page, size);
        return quoteMapper.demandHall(p, UserContext.farmerId(),
                keyword == null || keyword.isBlank() ? null : keyword.trim());
    }

    public List<Map<String, Object>> myQuotes() {
        return quoteMapper.myQuotes(UserContext.farmerId());
    }

    @Transactional
    public DemandQuote submit(QuoteDTO dto) {
        Long farmerId = UserContext.farmerId();
        PurchaseDemand d = demandMapper.selectById(dto.getDemandId());
        if (d == null) {
            throw new BizException(404, "采购需求不存在");
        }
        if (!"处理中".equals(d.getStatus())) {
            throw new BizException("该需求当前为「" + d.getStatus() + "」，不能报价");
        }
        if (d.getDeadline() != null && d.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BizException("该需求已过报价截止时间");
        }
        Farmer f = farmerMapper.selectById(farmerId);
        if (f == null) {
            throw new BizException(403, "当前账号未绑定农户档案");
        }
        if (!"已认证".equals(f.getStatus())) {
            throw new BizException("农户资质状态为「" + f.getStatus() + "」，认证通过后才能报价");
        }

        DemandQuote q = quoteMapper.selectOne(new LambdaQueryWrapper<DemandQuote>()
                .eq(DemandQuote::getDemandId, dto.getDemandId())
                .eq(DemandQuote::getFarmerId, farmerId));
        boolean isNew = q == null;
        if (isNew) {
            q = new DemandQuote();
            q.setDemandId(dto.getDemandId());
            q.setFarmerId(farmerId);
            q.setStatus("待选");
        } else if ("中标".equals(q.getStatus())) {
            throw new BizException("该报价已中标，不能修改");
        }
        q.setSourceId(dto.getSourceId());
        q.setPrice(dto.getPrice());
        q.setQuantity(dto.getQuantity());
        q.setDeliveryDays(dto.getDeliveryDays() == null ? 1 : dto.getDeliveryDays());
        q.setQualityNote(dto.getQualityNote());
        q.setRemark(dto.getRemark());
        if (isNew) {
            quoteMapper.insert(q);
        } else {
            quoteMapper.updateById(q);
        }
        refreshQuoteCount(d.getId());

        logService.record("需求报价", (isNew ? "提交" : "修改") + "报价：" + d.getDemandNo()
                + "，单价 ¥" + dto.getPrice() + "，可供 " + dto.getQuantity());
        return q;
    }

    /* ------------------------------------------------------------ 采购员端 */

    public Map<String, Object> compare(Long demandId) {
        PurchaseDemand d = demandMapper.selectById(demandId);
        if (d == null) {
            throw new BizException(404, "采购需求不存在");
        }
        if (!Objects.equals(d.getCanteenId(), UserContext.canteenId()) && !UserContext.isAdmin()) {
            throw new BizException(403, "无权查看其他食堂的需求报价");
        }
        List<Map<String, Object>> list = quoteMapper.quotesOfDemand(demandId);
        // 最低价、最高信用各标一个，前端不用自己算
        BigDecimal minPrice = null;
        BigDecimal maxCredit = null;
        for (Map<String, Object> m : list) {
            BigDecimal p = toDecimal(m.get("price"));
            BigDecimal c = toDecimal(m.get("creditScore"));
            if (p != null && (minPrice == null || p.compareTo(minPrice) < 0)) {
                minPrice = p;
            }
            if (c != null && (maxCredit == null || c.compareTo(maxCredit) > 0)) {
                maxCredit = c;
            }
        }
        for (Map<String, Object> m : list) {
            m.put("lowestPrice", minPrice != null && minPrice.compareTo(toDecimal(m.get("price"))) == 0);
            m.put("bestCredit", maxCredit != null && maxCredit.compareTo(toDecimal(m.get("creditScore"))) == 0);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("demand", d);
        res.put("quotes", list);
        res.put("quoteCount", list.size());
        return res;
    }

    /** 选定中标：生成采购单（回填 demand_id），并自动维护报价状态与需求状态 */
    @Transactional
    public PurchaseOrder award(Long demandId, Long quoteId) {
        PurchaseDemand d = demandMapper.selectById(demandId);
        if (d == null) {
            throw new BizException(404, "采购需求不存在");
        }
        if (!Objects.equals(d.getCanteenId(), UserContext.canteenId())) {
            throw new BizException(403, "只能为本食堂的需求定标");
        }
        if (!"处理中".equals(d.getStatus())) {
            throw new BizException("该需求当前为「" + d.getStatus() + "」，不能重复定标");
        }
        DemandQuote q = quoteMapper.selectById(quoteId);
        if (q == null || !Objects.equals(q.getDemandId(), demandId)) {
            throw new BizException(404, "报价不存在或不属于该需求");
        }
        if (!"待选".equals(q.getStatus())) {
            throw new BizException("该报价状态为「" + q.getStatus() + "」，不能定标");
        }

        Long sourceId = resolveSource(d, q);

        PurchaseOrderDTO dto = new PurchaseOrderDTO();
        dto.setDemandId(demandId);
        dto.setExpectedDelivery(d.getDeadline());
        dto.setRemark("由需求 " + d.getDemandNo() + " 定标生成");
        PurchaseOrderDTO.Item item = new PurchaseOrderDTO.Item();
        item.setSourceId(sourceId);
        item.setQuantity(q.getQuantity());
        dto.setItems(List.of(item));

        PurchaseOrder po = purchaseOrderService.create(dto);

        q.setStatus("中标");
        quoteMapper.updateById(q);
        for (Map<String, Object> other : quoteMapper.quotesOfDemand(demandId)) {
            Object idObj = other.get("id");
            if (idObj == null) {
                continue;
            }
            Long oid = Long.valueOf(String.valueOf(idObj));
            if (oid.equals(quoteId)) {
                continue;
            }
            DemandQuote oq = quoteMapper.selectById(oid);
            if (oq != null && !"中标".equals(oq.getStatus())) {
                oq.setStatus("落选");
                quoteMapper.updateById(oq);
            }
        }

        d.setStatus("已成交");
        demandMapper.updateById(d);
        refreshQuoteCount(demandId);

        logService.record("需求报价", "需求 " + d.getDemandNo() + " 定标给农户 #" + q.getFarmerId()
                + "，单价 ¥" + q.getPrice() + "，已生成采购单 " + po.getPoNo());
        return po;
    }

    /** 报价可能没绑货源：中标时按报价自动挂一条在售货源，保证履约链路能走通 */
    private Long resolveSource(PurchaseDemand d, DemandQuote q) {
        if (q.getSourceId() != null) {
            SupplySource s = sourceMapper.selectById(q.getSourceId());
            if (s != null && "正常".equals(s.getStatus())
                    && s.getQuantity() != null && s.getQuantity().compareTo(q.getQuantity()) >= 0) {
                return s.getId();
            }
        }
        Product p = productMapper.selectById(d.getProductId());
        SupplySource s = new SupplySource();
        s.setFarmerId(q.getFarmerId());
        s.setProductId(d.getProductId());
        s.setQuantity(q.getQuantity());
        s.setUnit(p == null ? "kg" : p.getUnit());
        s.setPrice(q.getPrice());
        s.setSupplyStart(LocalDate.now());
        s.setSupplyEnd(LocalDate.now().plusDays(7));
        s.setDeliveryMode(d.getDeliveryMode());
        s.setStorageReq("常温通风");
        s.setOriginRemark("需求定标自动挂牌");
        s.setSoldQuantity(BigDecimal.ZERO);
        s.setStatus("正常");
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        sourceMapper.insert(s);
        return s.getId();
    }

    private void refreshQuoteCount(Long demandId) {
        Long count = quoteMapper.selectCount(new LambdaQueryWrapper<DemandQuote>()
                .eq(DemandQuote::getDemandId, demandId));
        PurchaseDemand upd = new PurchaseDemand();
        upd.setId(demandId);
        upd.setQuoteCount(count == null ? 0 : count.intValue());
        demandMapper.updateById(upd);
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
