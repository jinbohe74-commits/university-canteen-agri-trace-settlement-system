package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.canteen.common.BizException;
import com.canteen.entity.SysConfig;
import com.canteen.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统参数（智能匹配权重、信用规则、预警阈值、功能开关）
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private static final String[] WEIGHT_KEYS = {
            "match_weight_category", "match_weight_price", "match_weight_quantity",
            "match_weight_credit", "match_weight_distance"
    };
    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "match_weight_category", 0.20,
            "match_weight_price", 0.25,
            "match_weight_quantity", 0.15,
            "match_weight_credit", 0.25,
            "match_weight_distance", 0.15
    );

    private final SysConfigMapper configMapper;

    public List<SysConfig> listAll() {
        return configMapper.selectList(new QueryWrapper<SysConfig>().orderByAsc("id"));
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (SysConfig c : listAll()) {
            map.put(c.getConfigKey(), c.getConfigValue());
        }
        return map;
    }

    public String get(String key, String defaultValue) {
        SysConfig c = configMapper.selectOne(new QueryWrapper<SysConfig>().eq("config_key", key));
        return c == null || c.getConfigValue() == null ? defaultValue : c.getConfigValue();
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 修复 P3：整数配置遇到 "3.9" 这类非整数值时不再静默截断，直接回退默认值。 */
    public int getInt(String key, int defaultValue) {
        try {
            String raw = get(key, String.valueOf(defaultValue)).trim();
            if (!raw.matches("-?\\d+")) {
                return defaultValue;
            }
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    @Transactional
    public void update(Map<String, String> values) {
        validateWeights(values);
        for (Map.Entry<String, String> e : values.entrySet()) {
            SysConfig c = configMapper.selectOne(new QueryWrapper<SysConfig>().eq("config_key", e.getKey()));
            if (c != null) {
                c.setConfigValue(e.getValue() == null ? "" : e.getValue());
                configMapper.updateById(c);
            } else {
                SysConfig nc = new SysConfig();
                nc.setConfigKey(e.getKey());
                nc.setConfigValue(e.getValue());
                nc.setConfigGroup("COMMON");
                nc.setValueType("string");
                configMapper.insert(nc);
            }
        }
    }

    /**
     * 修复 P1-7：写入时校验五项权重必须落在 [0,1] 且总和为 1，
     * 合并「本次提交值 + 数据库现值」后判定，避免拆分多次保存绕过校验。
     */
    private void validateWeights(Map<String, String> values) {
        boolean touchesWeights = false;
        double sum = 0;
        for (String key : WEIGHT_KEYS) {
            if (values.containsKey(key)) {
                touchesWeights = true;
            }
            String raw = values.containsKey(key) ? values.get(key)
                    : get(key, String.valueOf(DEFAULT_WEIGHTS.get(key)));
            double v;
            try {
                v = Double.parseDouble(raw == null ? "" : raw.trim());
            } catch (NumberFormatException e) {
                throw new BizException(400, "权重「" + key + "」必须是 0~1 之间的数字");
            }
            if (v < 0 || v > 1 || Double.isNaN(v)) {
                throw new BizException(400, "权重「" + key + "」必须在 0~1 之间");
            }
            sum += v;
        }
        if (touchesWeights && Math.abs(sum - 1.0) > 1e-6) {
            throw new BizException(400, "五项匹配权重之和必须等于 1（当前 " +
                    BigDecimal.valueOf(sum).setScale(4, RoundingMode.HALF_UP) + "）");
        }
    }

    /** 五因子权重：读取后做归一化保护，保证下游得分始终在 0~100。 */
    public Map<String, BigDecimal> matchWeights() {
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        raw.put("category", BigDecimal.valueOf(getDouble("match_weight_category", 0.20)));
        raw.put("price", BigDecimal.valueOf(getDouble("match_weight_price", 0.25)));
        raw.put("quantity", BigDecimal.valueOf(getDouble("match_weight_quantity", 0.15)));
        raw.put("credit", BigDecimal.valueOf(getDouble("match_weight_credit", 0.25)));
        raw.put("distance", BigDecimal.valueOf(getDouble("match_weight_distance", 0.15)));
        return normalizeWeights(raw);
    }

    /** 公开给 MatchService 复用：非法值归零，总和≠1 时按比例归一化，全非法时回退默认权重。 */
    public static Map<String, BigDecimal> normalizeWeights(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> cleaned = new LinkedHashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : raw.entrySet()) {
            BigDecimal v = e.getValue();
            if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
                v = BigDecimal.ZERO;
            }
            cleaned.put(e.getKey(), v);
            sum = sum.add(v);
        }
        if (sum.signum() <= 0) {
            return defaultWeightsMap();
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : cleaned.entrySet()) {
            normalized.put(e.getKey(), e.getValue().divide(sum, 6, RoundingMode.HALF_UP));
        }
        return normalized;
    }

    private static Map<String, BigDecimal> defaultWeightsMap() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("category", new BigDecimal("0.20"));
        m.put("price", new BigDecimal("0.25"));
        m.put("quantity", new BigDecimal("0.15"));
        m.put("credit", new BigDecimal("0.25"));
        m.put("distance", new BigDecimal("0.15"));
        return m;
    }
}
