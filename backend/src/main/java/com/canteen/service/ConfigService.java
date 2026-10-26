package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.canteen.entity.SysConfig;
import com.canteen.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统参数（智能匹配权重、信用规则、预警阈值、功能开关）
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

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

    public int getInt(String key, int defaultValue) {
        try {
            return (int) Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    @Transactional
    public void update(Map<String, String> values) {
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

    /** 五因子权重（带归一化保护） */
    public Map<String, BigDecimal> matchWeights() {
        BigDecimal w1 = BigDecimal.valueOf(getDouble("match_weight_category", 0.20));
        BigDecimal w2 = BigDecimal.valueOf(getDouble("match_weight_price", 0.25));
        BigDecimal w3 = BigDecimal.valueOf(getDouble("match_weight_quantity", 0.15));
        BigDecimal w4 = BigDecimal.valueOf(getDouble("match_weight_credit", 0.25));
        BigDecimal w5 = BigDecimal.valueOf(getDouble("match_weight_distance", 0.15));
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("category", w1);
        m.put("price", w2);
        m.put("quantity", w3);
        m.put("credit", w4);
        m.put("distance", w5);
        return m;
    }
}
