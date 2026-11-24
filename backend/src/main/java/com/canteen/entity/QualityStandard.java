package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分品类质检标准：一个品类一组检测项，每项有判限值与判定方式。
 *
 * <p>这张表取代了原来写死在 {@code QualityService.defaultItems()} 里的 6 条常量——
 * 那些常量对任何产品都给同一个「实测 92%」，是"随便点点就能过"的根源。</p>
 */
@Data
@TableName("quality_standard")
public class QualityStandard {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一级品类 id（product_category.parent_id = 0） */
    private Long categoryId;

    private String itemName;

    /** 检测方法 / 判定依据 */
    private String method;

    private String unit;

    /** 合格下限，null 表示不限 */
    private BigDecimal minValue;

    /** 合格上限，null 表示不限 */
    private BigDecimal maxValue;

    /** range=数值区间 / pass=感官 / equal=复核相符 */
    private String judgeType;

    /** 1=必检项 */
    private Integer required;

    /** 该品类建议抽检量(kg) */
    private BigDecimal sampleSpec;

    private Integer sort;

    private LocalDateTime createTime;
}
