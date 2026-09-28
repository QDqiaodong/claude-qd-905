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
}
