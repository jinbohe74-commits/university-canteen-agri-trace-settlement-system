package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.entity.Canteen;
import com.canteen.mapper.CanteenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食堂档案
 */
@Service
@RequiredArgsConstructor
public class CanteenService {

    private final CanteenMapper canteenMapper;
    private final LogService logService;

    public PageResult<Canteen> page(int page, int size, String keyword, String campus, String status) {
        LambdaQueryWrapper<Canteen> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(Canteen::getName, keyword).or().like(Canteen::getManager, keyword)
                    .or().like(Canteen::getCanteenNo, keyword));
        }
        if (StringUtils.hasText(campus)) {
            qw.eq(Canteen::getCampus, campus);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Canteen::getStatus, status);
        }
        qw.orderByAsc(Canteen::getId);
        IPage<Canteen> p = canteenMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Canteen> actives = canteenMapper.selectList(new LambdaQueryWrapper<Canteen>().eq(Canteen::getStatus, "正常"));
        m.put("active", actives.size());
        m.put("campuses", canteenMapper.selectList(null).stream().map(Canteen::getCampus).distinct().count());
        m.put("dailyMeals", actives.stream().mapToInt(Canteen::getScale).sum());
        m.put("monthOrders", canteenMapper.selectList(null).stream().mapToInt(Canteen::getMonthlyOrders).sum());
        m.put("total", canteenMapper.selectCount(null));
        return m;
    }

    public List<Canteen> listAll() {
        return canteenMapper.selectList(new LambdaQueryWrapper<Canteen>().eq(Canteen::getStatus, "正常"));
    }

    @Transactional
    public Canteen save(Canteen c) {
        if (c.getId() == null) {
            if (!StringUtils.hasText(c.getCanteenNo())) {
                c.setCanteenNo("C" + (991 + canteenMapper.selectCount(null)));
            }
            canteenMapper.insert(c);
            logService.record("基础档案", "新增食堂 " + c.getName());
        } else {
            canteenMapper.updateById(c);
            logService.record("基础档案", "编辑食堂档案 " + c.getName());
        }
        return c;
    }

    public Canteen detail(Long id) {
        Canteen c = canteenMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "食堂不存在");
        }
        return c;
    }
}
