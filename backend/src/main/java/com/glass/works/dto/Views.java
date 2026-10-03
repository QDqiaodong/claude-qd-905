package com.glass.works.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 页面视图对象：检验缺陷分布图、钢化炉当炉统计。 */
public final class Views {

    private Views() {
    }

    /** 缺陷分布图上的一个缺陷点 */
    @Data
    @NoArgsConstructor
    public static class DefectPointVO {
        private Long id;
        private String inspectNo;
        private String defectType;
        private String defectTypeName;
        private String color;
        private BigDecimal defectSizeMm;
        private BigDecimal posX;
        private BigDecimal posY;
        private String verdict;
        private String verdictName;
        private String batchNo;
        private String processType;
        private String processTypeName;
        private String inspector;
        private Integer sheetCount;
        /** 该实物批次当前共享的返修质量状态（开放返修时为 QUARANTINED/PROCESSING/INSPECTING） */
        private String reworkStatus;
        private String reworkStatusName;
    }

    /** 检验缺陷分布图整体视图 */
    @Data
    @NoArgsConstructor
    public static class DefectMapVO {
        private double plateRatio;
        private long total;
        private long passCount;
        private long reworkCount;
        private long scrapCount;
        private List<DefectPointVO> defects = new ArrayList<>();
        private List<DefectTypeStatDTO> typeStats = new ArrayList<>();
    }

    /** 炉体分段标注 */
    @Data
    @NoArgsConstructor
    public static class StageVO {
        private String code;
        private String name;
        private double temp;
        private int seconds;
        private String hint;

        public StageVO(String code, String name, double temp, int seconds, String hint) {
            this.code = code;
            this.name = name;
            this.temp = temp;
            this.seconds = seconds;
            this.hint = hint;
        }
    }

    /** 钢化炉当炉统计 */
    @Data
    @NoArgsConstructor
    public static class FurnaceVO {
        private Long id;
        private String batchNo;
        private String furnaceNo;
        private String glassType;
        private String glassTypeName;
        private Integer thicknessMm;
        private String tempRange;
        private Integer tempLow;
        private Integer tempHigh;
        private Integer heatTemp;
        private Integer heatSeconds;
        private BigDecimal quenchPressure;
        private BigDecimal warpage;
        private Integer particleCount;
        private Integer outputCount;
        private Integer brokenCount;
        private Integer goodCount;
        private Double brokenRate;
        private String stage;
        private String stageName;
        private String operator;
        private List<StageVO> stages = new ArrayList<>();
    }

    // ---------------- 返修质量闭环视图 ----------------

    /** 返修来源行（时间线里展示来源工序、片数、缺陷与归并理由） */
    @Data
    @NoArgsConstructor
    public static class ReworkSourceVO {
        private Long id;
        private Long inspectionId;
        private String inspectNo;
        private String sourceProcess;
        private String sourceProcessName;
        private String sourceBatchNo;
        private String sourceOrderNo;
        private String defectType;
        private String defectTypeName;
        private Integer sheetCount;
        private String inspector;
        private Integer roundNo;
        private String mergeReason;
        private String mergeReasonName;
        private String linkedAt;
    }

    /** 返修时间线事件 */
    @Data
    @NoArgsConstructor
    public static class ReworkEventVO {
        private Long id;
        private String eventType;
        private String eventTypeName;
        private Integer roundNo;
        private String fromStatus;
        private String fromStatusName;
        private String toStatus;
        private String toStatusName;
        private String actor;
        private String detail;
        private String conflictInspector;
        private String conflictVerdict;
        private String conflictVerdictName;
        private String winningVerdict;
        private String winningVerdictName;
        private String eventAt;
    }

    /** 归并日志条目（解释这张返修批次的来源是怎么并进来的） */
    @Data
    @NoArgsConstructor
    public static class ReworkMergeLogVO {
        private Long id;
        private String inspectNo;
        private String batchNo;
        private String orderNo;
        private Long mergedFromBatchId;
        private String reason;
        private String reasonName;
        private String detail;
        private String createdAt;
    }

    /** 返修批次列表 / 看板卡片 */
    @Data
    @NoArgsConstructor
    public static class ReworkBatchVO {
        private Long id;
        private String reworkNo;
        private String keyBatchNo;
        private String orderNos;
        private String isolationLocation;
        private String status;
        private String statusName;
        private Integer currentRound;
        private Integer totalSheets;
        private String dispositionMethod;
        private String dispositionProgress;
        private String disposedBy;
        private String isolatedAt;
        private String releasedAt;
        private String updatedAt;
        private Long version;
        private int sourceCount;
        private List<String> sourceProcesses = new ArrayList<>();
        private List<String> defects = new ArrayList<>();
        private List<ReworkSourceVO> sources = new ArrayList<>();
    }

    /** 批次时间线详情：批次头 + 来源 + 归并解释 + 事件流 */
    @Data
    @NoArgsConstructor
    public static class ReworkTimelineVO {
        private ReworkBatchVO batch;
        private List<ReworkSourceVO> sources = new ArrayList<>();
        private List<ReworkEventVO> events = new ArrayList<>();
        private List<ReworkMergeLogVO> mergeLogs = new ArrayList<>();
    }
}
