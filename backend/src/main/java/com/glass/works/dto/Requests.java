package com.glass.works.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 成品架、磨边、库位相关的入参与视图对象。 */
public final class Requests {

    private Requests() {
    }

    /** 成品架入参 */
    @Data
    @NoArgsConstructor
    public static class RackRequest {
        private String rackNo;
        private String orderNo;
        private String glassType;
        private Integer thicknessMm;
        private Integer lengthMm;
        private Integer widthMm;
        private Integer sheetCount;
        private BigDecimal maxLoadKg;
    }

    /** 成品架上架入参 */
    @Data
    @NoArgsConstructor
    public static class RackLoadRequest {
        private Integer sheetCount;
        private BigDecimal weightKg;
    }

    /** 磨边记录入参 */
    @Data
    @NoArgsConstructor
    public static class GrindingRequest {
        private String recordNo;
        private Long cuttingOrderId;
        private String batchNo;
        private String machine;
        private String edgeType;
        private String wheelModel;
        private BigDecimal feedSpeed;
        private Integer pieceCount;
        private Integer chipCount;
        private String chipNote;
        private String operator;
    }

    /** 原片入库位入参 */
    @Data
    @NoArgsConstructor
    public static class MoveRequest {
        private Long locationId;
    }

    // ---------------- 立体库位视图 ----------------

    /** 层 */
    @Data
    @NoArgsConstructor
    public static class LevelVO {
        private Long id;
        private Integer levelNo;
        private Integer batchCount;
        private Integer sheetCount;
        private BigDecimal maxLoadKg;
        private BigDecimal currentLoadKg;
        private BigDecimal remainingLoadKg;
        private Double loadRate;
        private String note;
        private List<BlockVO> blocks = new java.util.ArrayList<>();
    }

    /** 架 */
    @Data
    @NoArgsConstructor
    public static class RackVO {
        private String rackCode;
        private List<LevelVO> levels = new java.util.ArrayList<>();
    }

    /** 区 */
    @Data
    @NoArgsConstructor
    public static class ZoneVO {
        private String zoneCode;
        private String zoneName;
        private List<RackVO> racks = new java.util.ArrayList<>();
    }

    /** 立体图里的一个原片批次块 */
    @Data
    @NoArgsConstructor
    public static class BlockVO {
        private Long id;
        private String batchNo;
        private String glassType;
        private String glassTypeName;
        private String color;
        private Integer thicknessMm;
        private Integer lengthMm;
        private Integer widthMm;
        private Integer sheetCount;
        private String supplier;
        private BigDecimal transmittance;
        private String inboundDate;
        private String specText;
        private Double totalAreaSqm;
        private BigDecimal weightKg;
        private Long levelId;
    }

    /** 立体库位整体视图 */
    @Data
    @NoArgsConstructor
    public static class WarehouseVO {
        private List<ZoneVO> zones = new java.util.ArrayList<>();
        private long stockTotal;
        private long sheetTotal;
        private double areaTotal;
    }
}
