package com.glass.works.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 返修工作区入参。 */
public final class ReworkDtos {

    private ReworkDtos() {
    }

    /** 处置登记（隔离中 → 处理中，或继续登记处理中的处置进度） */
    @Data
    @NoArgsConstructor
    public static class DispositionRequest {
        /** 处置方式：复磨 / 返钢化 / 清洗干燥 / 换膜重镀 等 */
        private String method;
        /** 处置进度说明 */
        private String progress;
        /** 处置人 */
        private String operator;
        /** 本次处置片数（默认取该批总片数） */
        private Integer handledSheets;
        /** true 表示处置完成，进入待复检 */
        private Boolean finish;
    }

    /**
     * 复检结论提交。只有 PASS 才能放行；REWORK 回到隔离开始下一轮。
     * 两名检验员几乎同时提交相反结论时，后提交者得到明确冲突而非覆盖。
     */
    @Data
    @NoArgsConstructor
    public static class ReinspectRequest {
        /** PASS 合格放行 / REWORK 再判返修 */
        private String verdict;
        private String inspector;
        /** 复检备注（缺陷、尺寸、坐标等可写在备注里） */
        private String note;
        /** 复检发现缺陷类型 */
        private String defectType;
        private BigDecimal defectSizeMm;
        private Integer affectedSheets;
    }
}
