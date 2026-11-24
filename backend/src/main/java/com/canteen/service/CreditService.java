package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.entity.CreditScore;
import com.canteen.entity.Farmer;
import com.canteen.mapper.CreditScoreMapper;
import com.canteen.mapper.FarmerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 农户信用分（0~100，留存每一次加减分明细）
 */
@Service
@RequiredArgsConstructor
public class CreditService {

    private final FarmerMapper farmerMapper;
    private final CreditScoreMapper creditMapper;
    private final ConfigService configService;
    private final LogService logService;

    /**
     * 修复：change 为 null 时安全返回；同一订单 + 同一分值 + 同一原因只允许记一次，
     * 避免重复扣分（入库/质检回调被重放时不再叠加）。
     */
    @Transactional
    public void change(Long farmerId, Long orderId, BigDecimal change, String reason) {
        if (farmerId == null || change == null) {
            return;
        }
        if (orderId != null && creditMapper.selectCount(new LambdaQueryWrapper<CreditScore>()
                .eq(CreditScore::getFarmerId, farmerId)
                .eq(CreditScore::getOrderId, orderId)
                .eq(CreditScore::getChangeScore, change)
                .eq(CreditScore::getReason, reason)) > 0) {
            return;
        }
        Farmer f = farmerMapper.selectById(farmerId);
        if (f == null) {
            return;
        }
        BigDecimal before = f.getCreditScore() == null ? BigDecimal.valueOf(80) : f.getCreditScore();
        BigDecimal after = before.add(change);
        if (after.compareTo(BigDecimal.ZERO) < 0) {
            after = BigDecimal.ZERO;
        }
        if (after.compareTo(BigDecimal.valueOf(100)) > 0) {
            after = BigDecimal.valueOf(100);
        }
        CreditScore cs = new CreditScore();
        cs.setFarmerId(farmerId);
        cs.setOrderId(orderId);
        cs.setBeforeScore(before);
        cs.setChangeScore(change);
        cs.setAfterScore(after);
        cs.setReason(reason);
        cs.setCreateTime(LocalDateTime.now());
        creditMapper.insert(cs);

        Farmer upd = new Farmer();
        upd.setId(farmerId);
        upd.setCreditScore(after);
        farmerMapper.updateById(upd);

        // 审计闭环：信用分是农户的"经营资格"，改动必须能追到人。
        // 原来只写 credit_score 表、不写操作日志，导致管理员在「操作日志」里
        // 用农户姓名搜不到任何一条扣分记录，等于查不出是谁动的。
        logService.record("信用评价",
                String.format("调整农户「%s」信用分 %s → %s（%+.1f 分）；原因：%s%s",
                        f.getName(), strip(before), strip(after), change.doubleValue(), reason,
                        orderId == null ? "" : "；关联订单 " + orderId));
    }

    private static String strip(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    public void rewardNormal(Long farmerId, Long orderId) {
        change(farmerId, orderId, BigDecimal.valueOf(configService.getDouble("credit_reward_normal", 1)),
                "正常履约，按质完成订单");
    }

    public void punishLate(Long farmerId, Long orderId) {
        change(farmerId, orderId, BigDecimal.valueOf(configService.getDouble("credit_punish_late", -3)),
                "超时发货，未按约定时间履约");
    }

    public void punishQuality(Long farmerId, Long orderId) {
        change(farmerId, orderId, BigDecimal.valueOf(configService.getDouble("credit_punish_quality", -5)),
                "质检不合格，整批驳回");
    }

    public void punishLoss(Long farmerId, Long orderId) {
        change(farmerId, orderId, BigDecimal.valueOf(configService.getDouble("credit_punish_loss", -2)),
                "生鲜损耗率超过阈值");
    }
}
