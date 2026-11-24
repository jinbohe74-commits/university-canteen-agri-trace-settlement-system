package com.canteen.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质检提交。
 *
 * <p>与改造前的区别：</p>
 * <ul>
 *   <li>{@code items} 从 {@code List<Map>} 收紧为结构化的「检测项 + 实测值」，
 *       实测值 {@code input} 是必填——不填就没有判定依据。</li>
 *   <li>{@code result} 不再由前端说了算：后端按 {@code quality_standard} 的限值重新判定，
 *       前端传上来的结论只用于比对，不一致直接拒绝并指出是哪一项超限。</li>
 *   <li>不合格必须留 {@code remark} 与至少一张 {@code photos} 现场照片。</li>
 * </ul>
 */
@Data
public class QualitySubmitDTO {

    @NotNull(message = "请选择要质检的订单")
    private Long orderId;

    @NotBlank(message = "请填写批次号")
    @Size(max = 64, message = "批次号不能超过 64 字符")
    private String batchNo;

    @DecimalMin(value = "0.01", message = "抽检数量必须大于 0")
    private BigDecimal sampleQty;

    /** 前端展示用的结论；后端会按标准重新判定，不一致则拒绝提交 */
    private String result;

    /** 检测项与实测值，必须覆盖全部必检项 */
    private List<Item> items;

    @Size(max = 500, message = "备注不能超过 500 字")
    private String remark;

    private String reportFile;

    /** 现场照片地址（不合格时至少一张） */
    private List<String> photos;

    @Data
    public static class Item {
        /** 检测项名称，必须能在该品类的质量标准里找到 */
        private String itemName;
        /** 实测值：数值项填数字，感官项填「合格/不合格」，复核项填「相符/不符」 */
        private String input;
        /** 该项备注（异常说明等） */
        private String note;
    }
}
