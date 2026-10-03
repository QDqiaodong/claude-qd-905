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
 * 返修批次时间线事件：隔离、归并、开工、处置、复检放行 / 再判返修、并发冲突全程留痕。
 * 两名检验员几乎同时提交相反结论时，胜出方推进状态，落败方留 CONFLICT 事件，全过程可审计。
 */
@Entity
@Table(name = "rework_event")
@Getter
@Setter
public class ReworkEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** ISOLATE / MERGE / START / DISPOSE / RELEASE / REWORK_BACK / REOPEN / CONFLICT */
    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    /** 事件发生时所处返修轮次 */
    @Column(name = "round_no")
    private Integer roundNo;

    /** 事件发生前状态 */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    /** 事件发生后状态 */
    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "actor", length = 32)
    private String actor;

    @Column(name = "detail", length = 512)
    private String detail;

    /** 乐观锁冲突时落败提交携带的检验员 / 结论，用于 CONFLICT 事件对拍 */
    @Column(name = "conflict_inspector", length = 32)
    private String conflictInspector;

    @Column(name = "conflict_verdict", length = 8)
    private String conflictVerdict;

    /** 胜出结论（CONFLICT 事件记录当时唯一有效的最终结论） */
    @Column(name = "winning_verdict", length = 8)
    private String winningVerdict;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
