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
 * 返修归并日志：每笔判返修检验记录如何并入（或不并入）返修批次都留一条解释。
 * 解决“历史记录有的只有批次号、有的只有订单号”的可解释取舍：
 * 既不误合并不同实物，也不让旧记录整体失联。
 */
@Entity
@Table(name = "rework_merge_log")
@Getter
@Setter
public class ReworkMergeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspect_no", length = 32)
    private String inspectNo;

    @Column(name = "batch_no", length = 32)
    private String batchNo;

    @Column(name = "order_no", length = 32)
    private String orderNo;

    /** 归并后所属返修批次 */
    @Column(name = "batch_id")
    private Long batchId;

    /** 若发生批次合并，被并入的批次 id */
    @Column(name = "merged_from_batch_id")
    private Long mergedFromBatchId;

    /** 归并理由，见 Dict.MR_* */
    @Column(name = "reason", length = 32)
    private String reason;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
