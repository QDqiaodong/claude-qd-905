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
 * 返修批次的归并身份：一张返修批次可同时拥有批次号（强标识）与若干订单号（弱标识）。
 * 归并时用“批次号优先、订单号桥接但不得撞批次”的规则做并查集取舍。
 */
@Entity
@Table(name = "rework_identity")
@Getter
@Setter
public class ReworkIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** BATCH 实物批次号 / ORDER 订单号（关联单号） */
    @Column(name = "identity_type", nullable = false, length = 8)
    private String identityType;

    @Column(name = "identity_value", nullable = false, length = 32)
    private String identityValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
