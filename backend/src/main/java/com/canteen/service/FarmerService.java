package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.UserContext;
import com.canteen.entity.Farmer;
import com.canteen.entity.SupplySource;
import com.canteen.entity.SysUser;
import com.canteen.mapper.FarmerMapper;
import com.canteen.mapper.SupplySourceMapper;
import com.canteen.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农户/合作社档案
 */
@Service
@RequiredArgsConstructor
public class FarmerService {

    private final FarmerMapper farmerMapper;
    private final SupplySourceMapper sourceMapper;
    private final SysUserMapper userMapper;
    private final LogService logService;

    public PageResult<Farmer> page(int page, int size, String keyword, String type, String status, String creditLevel) {
        LambdaQueryWrapper<Farmer> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(Farmer::getName, keyword).or().like(Farmer::getContact, keyword)
                    .or().like(Farmer::getPhone, keyword).or().like(Farmer::getFarmerNo, keyword));
        }
        if (StringUtils.hasText(type)) {
            qw.eq(Farmer::getType, type);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Farmer::getStatus, status);
        }
        if (StringUtils.hasText(creditLevel)) {
            switch (creditLevel) {
                case "优秀" -> qw.ge(Farmer::getCreditScore, 90);
                case "良好" -> qw.ge(Farmer::getCreditScore, 85).lt(Farmer::getCreditScore, 90);
                case "一般" -> qw.ge(Farmer::getCreditScore, 80).lt(Farmer::getCreditScore, 85);
                case "风险" -> qw.lt(Farmer::getCreditScore, 80);
                default -> {
                }
            }
        }
        qw.orderByAsc(Farmer::getId);
        IPage<Farmer> p = farmerMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long certified = farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>().eq(Farmer::getStatus, "已认证"));
        long bases = farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>()
                .in(Farmer::getType, List.of("生产基地", "合作社")));
        m.put("certified", certified);
        m.put("bases", bases);
        m.put("pending", farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>().eq(Farmer::getStatus, "待审核")));
        m.put("total", farmerMapper.selectCount(null));
        List<Farmer> list = farmerMapper.selectList(new LambdaQueryWrapper<Farmer>()
                .eq(Farmer::getStatus, "已认证"));
        BigDecimal avg = BigDecimal.ZERO;
        if (!list.isEmpty()) {
            avg = list.stream().map(Farmer::getCreditScore).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(list.size()), 1, RoundingMode.HALF_UP);
        }
        m.put("avgCredit", avg);
        List<Map<String, Object>> types = List.of(
                Map.of("name", "合作社", "value", farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>().eq(Farmer::getType, "合作社"))),
                Map.of("name", "生产基地", "value", farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>().eq(Farmer::getType, "生产基地"))),
                Map.of("name", "个体农户", "value", farmerMapper.selectCount(new LambdaQueryWrapper<Farmer>().eq(Farmer::getType, "个体农户"))));
        m.put("typeCount", types);
        return m;
    }

    public Farmer detail(Long id) {
        Farmer f = farmerMapper.selectById(id);
        if (f == null) {
            throw new BizException(404, "农户不存在");
        }
        return f;
    }

    @Transactional
    public Farmer save(Farmer f) {
        if (f.getId() == null) {
            if (!StringUtils.hasText(f.getFarmerNo())) {
                long count = farmerMapper.selectCount(null);
                f.setFarmerNo("F9" + (930 + count));
            }
            if (f.getCreditScore() == null) {
                f.setCreditScore(BigDecimal.valueOf(80));
            }
            if (!StringUtils.hasText(f.getStatus())) {
                f.setStatus("待审核");
            }
            f.setJoinTime(LocalDateTime.now());
            farmerMapper.insert(f);
            logService.record("基础档案", "新增农户 " + f.getName());
        } else {
            Farmer old = farmerMapper.selectById(f.getId());
            if (old == null) {
                throw new BizException(404, "农户不存在");
            }
            f.setFarmerNo(old.getFarmerNo());
            farmerMapper.updateById(f);
            logService.record("基础档案", "编辑农户档案 " + f.getName());
        }
        return f;
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        Farmer f = farmerMapper.selectById(id);
        if (f == null) {
            throw new BizException(404, "农户不存在");
        }
        f.setStatus(status);
        farmerMapper.updateById(f);
        if (f.getUserId() != null) {
            SysUser upd = new SysUser();
            upd.setId(f.getUserId());
            upd.setStatus("已停用".equals(status) ? 0 : 1);
            userMapper.updateById(upd);
        }
        logService.record("基础档案", "农户 " + f.getName() + " 状态变更为 " + status);
    }

    /** 农户端：当前登录农户 */
    public Farmer current() {
        Farmer f = farmerMapper.selectById(UserContext.farmerId());
        if (f == null) {
            throw new BizException(404, "农户档案不存在");
        }
        return f;
    }

    /** 当前农户的货源统计 */
    public Map<String, Object> sourceStats() {
        Long fid = UserContext.farmerId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onSale", sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid).eq(SupplySource::getStatus, "正常")));
        m.put("total", sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid)));
        m.put("soldOut", sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid).eq(SupplySource::getStatus, "已售罄")));
        List<SupplySource> all = sourceMapper.selectList(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid).eq(SupplySource::getStatus, "正常"));
        m.put("stock", all.stream().map(SupplySource::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("monthDeals", all.size() * 8);
        return m;
    }
}
