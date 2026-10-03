package com.glass.works.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 返修来源记录：每条来源对应一笔判返修的检验单，保留来源工序、涉及片数、缺陷类型，
 * 以及当时的批次号 / 订单号原值与归并理由。一条检验单只属于一张返修批次。
 */
@Entity
@Table(name = "rework_source")
@Getter
@Setter
public class ReworkSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 关联的检验记录 id（复检轮次内产生的再判返修也同样落来源） */
    @Column(name = "inspection_id")
    private Long inspectionId;

    @Column(name = "inspect_no", length = 32)
    private String inspectNo;

    /** 来源工序环节：CUTTING / GRINDING / TEMPERING / COATING / INSULATING / LAMINATING */
    @Column(name = "source_process", length = 16)
    private String sourceProcess;

    @Column(name = "source_batch_no", length = 32)
    private String sourceBatchNo;

    @Column(name = "source_order_no", length = 32)
    private String sourceOrderNo;

    @Column(name = "defect_type", length = 16)
    private String defectType;

    @Column(name = "sheet_count")
    private Integer sheetCount;

    @Column(name = "inspector", length = 32)
    private String inspector;

    /** 第几轮返修（复检再判返修时递增） */
    @Column(name = "round_no")
    private Integer roundNo;

    /** 归并理由，见 Dict.MR_* */
    @Column(name = "merge_reason", length = 32)
    private String mergeReason;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;
}
