package com.glass.works.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 返修批次：检验、深加工与成品架共享的同一份批次质量状态。
 *
 * <p>来源可追溯：每张返修批次由若干 {@link ReworkSource}（来源工序检验单）构成，
 * 历史检验记录可能只有批次号或只有订单号，归并取舍过程逐笔记录在 rework_merge_log。</p>
 *
 * <p>受控流转：QUARANTINED 隔离中 → PROCESSING 处理中 → INSPECTING 待复检
 * → RELEASED 已放行；复检再判返修回到 QUARANTINED；放行后再次判返修重新隔离。
 * MERGED 表示经桥接归入其他批次，行保留用于审计。</p>
 *
 * <p>{@code version} 为 JPA 乐观锁；复检提交的服务方法同时使用行级悲观写锁串行化，
 * 两名检验员几乎同时提交相反结论时只产生一个有效最终状态。</p>
 */
@Entity
@Table(name = "rework_batch")
@Getter
@Setter
public class ReworkBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 返修单号：RW- 前缀 */
    @Column(name = "rework_no", nullable = false, unique = true, length = 32)
    private String reworkNo;

    /** 实物批次强标识；仅订单号归并、批次跨多张实物时为 null */
    @Column(name = "key_batch_no", length = 32)
    private String keyBatchNo;

    /** 逗号分隔的该批次出现过的订单号 / 关联单号 */
    @Column(name = "order_nos", length = 256)
    private String orderNos;

    /** 隔离区库位，如 隔离区-01 */
    @Column(name = "isolation_location", length = 32)
    private String isolationLocation;

    /** 当前受控状态 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 处置返修轮次：每轮 隔离→处置→复检，再判返修递增 */
    @Column(name = "current_round")
    private Integer currentRound;

    /** 来源涉及片数合计 */
    @Column(name = "total_sheets")
    private Integer totalSheets;

    /** 最新处置方式说明 */
    @Column(name = "disposition_method", length = 128)
    private String dispositionMethod;

    /** 最新处置进度说明 */
    @Column(name = "disposition_progress", length = 128)
    private String dispositionProgress;

    /** 处置人 */
    @Column(name = "disposed_by", length = 32)
    private String disposedBy;

    @Column(name = "isolated_at")
    private LocalDateTime isolatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 乐观锁版本：并发状态写入冲突时后写者收到明确冲突，而非静默覆盖 */
    @Version
    @Column(name = "version")
    private Long version;

    @Transient
    public String getStatusName() {
        return com.glass.works.common.Dict.reworkStatusName(status);
    }
}
