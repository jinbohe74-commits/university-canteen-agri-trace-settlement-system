package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.canteen.entity.Canteen;
import com.canteen.entity.Farmer;
import com.canteen.entity.InboundRecord;
import com.canteen.entity.Product;
import com.canteen.entity.ProductCategory;
import com.canteen.entity.Settlement;
import com.canteen.mapper.CanteenMapper;
import com.canteen.mapper.FarmerMapper;
import com.canteen.mapper.InboundRecordMapper;
import com.canteen.mapper.ProductCategoryMapper;
import com.canteen.mapper.ProductMapper;
import com.canteen.mapper.SettlementMapper;
import com.canteen.vo.OrderVO;
import com.canteen.entity.Orders;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基础参照数据（品类、产品、农户、食堂）与订单视图装配
 */
@Service
@RequiredArgsConstructor
public class RefService {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final InboundRecordMapper inboundMapper;
    private final SettlementMapper settlementMapper;

    /** 订单状态字典 */
    public static final Map<Integer, String> ORDER_STATUS = new java.util.LinkedHashMap<>() {{
        put(10, "待接单");
        put(20, "已接单");
        put(30, "待质检");
        put(40, "质检通过");
        put(41, "质检不通过");
        put(50, "配送中");
        put(60, "已送达");
        put(70, "已入库");
        put(80, "待结算");
        put(90, "已结算");
        put(99, "已取消");
        put(100, "已完成");
    }};

    public static String statusName(Integer status) {
        return ORDER_STATUS.getOrDefault(status, "未知状态");
    }

    public Map<Long, Product> products() {
        return productMapper.selectList(null).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
    }

    public Map<Long, ProductCategory> categories() {
        return categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(ProductCategory::getId, Function.identity(), (a, b) -> a));
    }

    public Map<Long, Farmer> farmers() {
        return farmerMapper.selectList(null).stream()
                .collect(Collectors.toMap(Farmer::getId, Function.identity(), (a, b) -> a));
    }

    public Map<Long, Canteen> canteens() {
        return canteenMapper.selectList(null).stream()
                .collect(Collectors.toMap(Canteen::getId, Function.identity(), (a, b) -> a));
    }

    /** 产品 -> 大类名称 */
    public String bigCategoryName(Long productId, Map<Long, Product> products, Map<Long, ProductCategory> categories) {
        Product p = products.get(productId);
        if (p == null) {
            return "";
        }
        ProductCategory sub = categories.get(p.getCategoryId());
        if (sub == null) {
            return "";
        }
        if (sub.getParentId() == null || sub.getParentId() == 0) {
            return sub.getCategoryName();
        }
        ProductCategory big = categories.get(sub.getParentId());
        return big == null ? sub.getCategoryName() : big.getCategoryName();
    }

    /** 批量装配订单视图（避免 N+1） */
    public List<OrderVO> toOrderVO(List<Orders> list) {
        Map<Long, Product> products = products();
        Map<Long, ProductCategory> cats = categories();
        Map<Long, Farmer> farmers = farmers();
        Map<Long, Canteen> canteens = canteens();
        Map<Long, InboundRecord> inbounds = new HashMap<>();
        Map<Long, Settlement> settlements = new HashMap<>();
        if (!list.isEmpty()) {
            List<Long> ids = list.stream().map(Orders::getId).toList();
            for (InboundRecord r : inboundMapper.selectList(new QueryWrapper<InboundRecord>().in("order_id", ids))) {
                inbounds.put(r.getOrderId(), r);
            }
            for (Settlement s : settlementMapper.selectList(new QueryWrapper<Settlement>().in("order_id", ids))) {
                settlements.put(s.getOrderId(), s);
            }
        }
        return list.stream().map(o -> {
            OrderVO v = new OrderVO();
            org.springframework.beans.BeanUtils.copyProperties(o, v);
            Product p = products.get(o.getProductId());
            v.setProductName(p == null ? "-" : p.getName());
            v.setUnit(p == null ? "kg" : p.getUnit());
            v.setCategoryName(bigCategoryName(o.getProductId(), products, cats));
            Farmer f = farmers.get(o.getFarmerId());
            v.setFarmerName(f == null ? "-" : f.getName());
            v.setFarmerType(f == null ? "" : f.getType());
            Canteen c = canteens.get(o.getCanteenId());
            v.setCanteenName(c == null ? "-" : c.getName());
            v.setStatusName(statusName(o.getStatus()));
            InboundRecord ir = inbounds.get(o.getId());
            if (ir != null) {
                v.setReceiveQuantity(ir.getReceiveQuantity());
                v.setLossQuantity(ir.getLossQuantity());
                v.setLossRate(ir.getLossRate());
            }
            Settlement st = settlements.get(o.getId());
            if (st != null) {
                v.setSettlementId(st.getId());
                v.setSettlementStatus(st.getStatus());
            }
            return v;
        }).toList();
    }

    public OrderVO toOrderVO(Orders o) {
        return toOrderVO(List.of(o)).get(0);
    }

    public List<Map<String, Object>> orderStatusDict() {
        return ORDER_STATUS.entrySet().stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("value", e.getKey());
            m.put("label", e.getValue());
            return m;
        }).toList();
    }
}
