package com.canteen.service;

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

    @Transactional
    public void change(Long farmerId, Long orderId, BigDecimal change, String reason) {
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
