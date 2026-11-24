package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.UserContext;
import com.canteen.entity.Warning;
import com.canteen.mapper.StatMapper;
import com.canteen.mapper.WarningMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食安预警：证照 / 质检 / 价格 / 逾期
 */
@Service
@RequiredArgsConstructor
public class WarningService {

    private final WarningMapper warningMapper;
    private final StatMapper statMapper;
    private final LogService logService;

    public PageResult<Warning> page(int page, int size, String type, String level, String status, String keyword) {
        LambdaQueryWrapper<Warning> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(type)) {
            qw.eq(Warning::getWarningType, type);
        }
        if (StringUtils.hasText(level)) {
            qw.eq(Warning::getRiskLevel, level);
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Warning::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            // 预警编号也要能搜：待办/通知给出的是编号，搜不到就等于"给了线索却查无此单"
            qw.and(w -> w.like(Warning::getWarningNo, keyword)
                    .or().like(Warning::getTarget, keyword)
                    .or().like(Warning::getContent, keyword));
        }
        // 未处置的永远排前面：历史已完成 77 条、待处置 24 条，
        // 只按时间倒序会让"处置完的先出现"，把真正要干的挤下去。
        qw.last("ORDER BY FIELD(status,'待处理','待确认','已完成','已忽略'), trigger_time DESC");
        IPage<Warning> p = warningMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", warningMapper.selectCount(null));
        m.put("pending", warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getStatus, "待处理")));
        m.put("cert", warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getWarningType, "证照预警").eq(Warning::getStatus, "待处理")));
        m.put("quality", warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getWarningType, "质检预警").eq(Warning::getStatus, "待处理")));
        m.put("price", warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getWarningType, "价格预警").eq(Warning::getStatus, "待处理")));
        m.put("overdue", warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getWarningType, "逾期预警").eq(Warning::getStatus, "待处理")));
        m.put("typeCount", statMapper.warningTypeCount());
        m.put("levelCount", statMapper.warningLevelCount());
        m.put("trend", statMapper.warningTrend());
        m.put("expiringCerts", statMapper.expiringCerts(30));
        return m;
    }

    public Map<String, Object> detail(Long id) {
        Warning w = warningMapper.selectById(id);
        if (w == null) {
            throw new BizException(404, "预警不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("warning", w);
        m.put("suggestion", switch (w.getWarningType()) {
            case "证照预警" -> "联系农户补充有效证照，逾期未补将停用账号";
            case "质检预警" -> "该批次已驳回，联系农户整改并复检，必要时触发退货";
            case "价格预警" -> "核验报价合理性，必要时调整参考价并提示采购员比价";
            default -> "催办农户尽快发货，超时自动扣减信用分";
        });
        return m;
    }

    @Transactional
    public void handle(Long id, String status, String remark) {
        Warning w = warningMapper.selectById(id);
        if (w == null) {
            throw new BizException(404, "预警不存在");
        }
        w.setStatus(status);
        w.setHandler(UserContext.get().getRealName());
        w.setHandleTime(LocalDateTime.now());
        w.setHandleRemark(remark);
        warningMapper.updateById(w);
        logService.record("预警管理", "处置预警 " + w.getWarningNo() + " -> " + status);
    }

    @Transactional
    public Map<String, Object> batchHandle(List<Long> ids, String status) {
        int n = 0;
        for (Long id : ids) {
            Warning w = warningMapper.selectById(id);
            if (w != null) {
                w.setStatus(status);
                w.setHandler(UserContext.get().getRealName());
                w.setHandleTime(LocalDateTime.now());
                warningMapper.updateById(w);
                n++;
            }
        }
        logService.record("预警管理", "批量处置预警 " + n + " 条 -> " + status);
        return Map.of("handled", n);
    }

    @Transactional
    public void create(String level, String type, String target, String content) {
        create(level, type, target, content, null);
    }

    /** 带现场照片的重载：异常上报要走这条，照片与预警一起留痕 */
    @Transactional
    public void create(String level, String type, String target, String content, List<String> photoUrls) {
        Warning w = new Warning();
        String yp = "YJ" + java.time.LocalDate.now().toString().replace("-", "");
        w.setWarningNo(com.canteen.common.Codes.next(yp, 3, (int) (warningMapper.selectCount(null) % 800 + 100),
                code -> warningMapper.selectCount(new LambdaQueryWrapper<Warning>().eq(Warning::getWarningNo, code)) > 0));
        w.setRiskLevel(level);
        w.setWarningType(type);
        w.setTarget(target);
        w.setContent(content);
        if (photoUrls != null && !photoUrls.isEmpty()) {
            w.setPhotoUrls(String.join(";", photoUrls));
        }
        w.setStatus("待处理");
        w.setTriggerTime(LocalDateTime.now());
        warningMapper.insert(w);
    }

    public List<Warning> latest(int limit) {
        return warningMapper.selectList(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getStatus, "待处理").orderByDesc(Warning::getTriggerTime).last("LIMIT " + limit));
    }
}

