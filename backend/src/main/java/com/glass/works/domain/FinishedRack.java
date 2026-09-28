package com.glass.works.domain;

import com.glass.works.common.BizException;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 成品架：已检验合格的成品玻璃按订单集中的发货架位。
 *
 * <p>派生属性 {@code area_sqm}（面积 ㎡）走 {@code @Access(PROPERTY)}：
 * getter 由 长 × 宽 × 片数 / 1,000,000 得出，
 * setter 反向校验 —— 库里的面积与规格片数不符（差值超过 0.05㎡ 或 1%）直接抛业务异常。</p>
 */
@Entity
@Table(name = "finished_rack")
@Access(AccessType.FIELD)
@Getter
@Setter
public class FinishedRack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 架号 */
    @Column(name = "rack_no", nullable = false, unique = true, length = 16)
    private String rackNo;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "glass_type", length = 32)
    private String glassType;

    @Column(name = "thickness_mm")
    private Integer thicknessMm;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "sheet_count")
    private Integer sheetCount;

    @Column(name = "max_load_kg", precision = 12, scale = 2)
    private BigDecimal maxLoadKg;

    @Column(name = "current_load_kg", precision = 12, scale = 2)
    private BigDecimal currentLoadKg;

    /** PENDING 待备货 / READY 待发货 / SHIPPED 已发货 */
    @Column(name = "ship_status", length = 16)
    private String shipStatus;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    /** 派生列（面积 ㎡），走属性访问 */
    private BigDecimal areaSqm;

    @Access(AccessType.PROPERTY)
    @Column(name = "area_sqm", precision = 12, scale = 2)
    public BigDecimal getAreaSqm() {
        if (lengthMm == null || widthMm == null || sheetCount == null) {
            return areaSqm;
        }
        double area = lengthMm.doubleValue() * widthMm.doubleValue() * sheetCount / 1_000_000.0;
        return BigDecimal.valueOf(area).setScale(2, RoundingMode.HALF_UP);
    }

    public void setAreaSqm(BigDecimal value) {
        if (value == null) {
            this.areaSqm = null;
            return;
        }
        // 规格与片数齐全时反校验库里的派生值是否与规格一致
        if (lengthMm != null && widthMm != null && sheetCount != null
                && lengthMm > 0 && widthMm > 0 && sheetCount > 0) {
            double expect = lengthMm.doubleValue() * widthMm.doubleValue() * sheetCount / 1_000_000.0;
            double diff = Math.abs(expect - value.doubleValue());
            if (diff > 0.05 && diff / expect > 0.01) {
                throw new BizException("成品架面积 " + value + "㎡ 与规格 "
                        + lengthMm + "×" + widthMm + "×" + sheetCount + "片 不符（应约 "
                        + BigDecimal.valueOf(expect).setScale(2, RoundingMode.HALF_UP) + "㎡）");
            }
        }
        this.areaSqm = value.setScale(2, RoundingMode.HALF_UP);
    }

    @Transient
    public String getSpecText() {
        return (thicknessMm == null ? "0" : thicknessMm) + "mm "
                + (lengthMm == null ? "0" : lengthMm) + "×" + (widthMm == null ? "0" : widthMm);
    }

    /** 剩余承重 kg */
    @Transient
    public BigDecimal getRemainingLoadKg() {
        BigDecimal max = maxLoadKg == null ? BigDecimal.ZERO : maxLoadKg;
        BigDecimal cur = currentLoadKg == null ? BigDecimal.ZERO : currentLoadKg;
        return max.subtract(cur).setScale(2, RoundingMode.HALF_UP);
    }

    /** 载荷率 % */
    @Transient
    public Double getLoadRate() {
        if (maxLoadKg == null || maxLoadKg.doubleValue() <= 0) {
            return 0.0;
        }
        double cur = currentLoadKg == null ? 0 : currentLoadKg.doubleValue();
        return Math.round(cur / maxLoadKg.doubleValue() * 1000.0) / 10.0;
    }

    /** 按 2.5kg/(㎡·mm) 估算整架重量 */
    @Transient
    public BigDecimal getEstimatedWeightKg() {
        BigDecimal area = getAreaSqm();
        if (area == null || thicknessMm == null) {
            return BigDecimal.ZERO;
        }
        return area.multiply(BigDecimal.valueOf(thicknessMm))
                .multiply(BigDecimal.valueOf(2.5))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
