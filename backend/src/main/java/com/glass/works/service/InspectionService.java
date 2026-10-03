package com.glass.works.service;

import com.glass.works.common.BizException;
import com.glass.works.common.Dict;
import com.glass.works.domain.FinishedRack;
import com.glass.works.domain.InspectionRecord;
import com.glass.works.dto.DefectTypeStatDTO;
import com.glass.works.dto.InspectionRequest;
import com.glass.works.dto.RackStatDTO;
import com.glass.works.dto.Requests.RackLoadRequest;
import com.glass.works.dto.Requests.RackRequest;
import com.glass.works.dto.Views.DefectMapVO;
import com.glass.works.dto.Views.DefectPointVO;
import com.glass.works.repository.FinishedRackRepository;
import com.glass.works.repository.InspectionRecordRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 检验与成品架：缺陷分布、判定统计、成品架承重校验与发货。 */
@Service
public class InspectionService {

    private final InspectionRecordRepository inspectionRepository;
    private final FinishedRackRepository rackRepository;
    private final ReworkService reworkService;

    public InspectionService(InspectionRecordRepository inspectionRepository,
                             FinishedRackRepository rackRepository,
                             @Lazy ReworkService reworkService) {
        this.inspectionRepository = inspectionRepository;
        this.rackRepository = rackRepository;
        this.reworkService = reworkService;
    }

    // ---------------- 检验 ----------------

    public List<InspectionRecord> list(String processType) {
        return inspectionRepository.filter(StringUtils.hasText(processType) ? processType : null);
    }

    public List<DefectTypeStatDTO> defectStat() {
        return inspectionRepository.statByDefectType();
    }

    @Transactional
    public InspectionRecord create(InspectionRequest request) {
        if (!StringUtils.hasText(request.getInspectNo())) {
            throw new BizException("检验单号不能为空");
        }
        if (!Dict.isValidWorkStage(request.getProcessType())) {
            throw new BizException("工序环节非法：" + request.getProcessType());
        }
        if (!Dict.isValidDefectType(request.getDefectType())) {
            throw new BizException("缺陷类型非法：" + request.getDefectType());
        }
        if (!Dict.isValidVerdict(request.getVerdict())) {
            throw new BizException("判定非法：" + request.getVerdict());
        }
        if (request.getDefectSizeMm() == null || request.getDefectSizeMm().doubleValue() <= 0) {
            throw new BizException("缺陷尺寸必须为正数");
        }
        if (inspectionRepository.existsByInspectNo(request.getInspectNo())) {
            throw new BizException("检验单号已存在：" + request.getInspectNo());
        }
        InspectionRecord record = new InspectionRecord();
        record.setInspectNo(request.getInspectNo());
        record.setProcessType(request.getProcessType());
        record.setBatchNo(request.getBatchNo());
        record.setOrderNo(request.getOrderNo());
        record.setDefectType(request.getDefectType());
        record.setDefectSizeMm(request.getDefectSizeMm().setScale(2, RoundingMode.HALF_UP));
        record.setPosX(clampPercent(request.getPosX()));
        record.setPosY(clampPercent(request.getPosY()));
        record.setVerdict(request.getVerdict());
        record.setInspector(request.getInspector());
        record.setSheetCount(request.getSheetCount() == null ? 1 : request.getSheetCount());
        record.setInspectedAt(LocalDateTime.now());
        record = inspectionRepository.save(record);
        // 任一工序判返修：立即形成可追溯的返修隔离记录（同事务，失败一起回滚）
        if (Dict.VERDICT_REWORK.equals(record.getVerdict())) {
            reworkService.ingest(record);
        }
        return record;
    }

    /** 缺陷分布图：玻璃板平面 + 缺陷打点 + 类型占比 */
    public DefectMapVO defectMap(String processType) {
        List<InspectionRecord> records = list(processType);
        DefectMapVO vo = new DefectMapVO();
        vo.setPlateRatio(2440.0 / 1830.0);
        long pass = 0;
        long rework = 0;
        long scrap = 0;
        // 检验页共享批次质量状态：打点上直接带出该实物批次当前是否仍在返修隔离
        Set<String> batchNos = new HashSet<>();
        for (InspectionRecord r : records) {
            if (StringUtils.hasText(r.getBatchNo())) {
                batchNos.add(r.getBatchNo().trim());
            }
        }
        Map<String, String> qualityStatus = reworkService.openStatusByBatchNo(batchNos);
        for (InspectionRecord record : records) {
            DefectPointVO point = new DefectPointVO();
            point.setId(record.getId());
            point.setInspectNo(record.getInspectNo());
            point.setDefectType(record.getDefectType());
            point.setDefectTypeName(Dict.defectTypeName(record.getDefectType()));
            point.setColor(Dict.defectTypeColor(record.getDefectType()));
            point.setDefectSizeMm(record.getDefectSizeMm());
            point.setPosX(record.getPosX());
            point.setPosY(record.getPosY());
            point.setVerdict(record.getVerdict());
            point.setVerdictName(record.getVerdictName());
            point.setBatchNo(record.getBatchNo());
            point.setProcessType(record.getProcessType());
            point.setProcessTypeName(Dict.workStageName(record.getProcessType()));
            point.setInspector(record.getInspector());
            point.setSheetCount(record.getSheetCount());
            if (StringUtils.hasText(record.getBatchNo())) {
                String rwStatus = qualityStatus.get(record.getBatchNo().trim());
                point.setReworkStatus(rwStatus);
                point.setReworkStatusName(Dict.reworkStatusName(rwStatus));
            }
            vo.getDefects().add(point);
            if (Dict.VERDICT_PASS.equals(record.getVerdict())) {
                pass++;
            } else if (Dict.VERDICT_REWORK.equals(record.getVerdict())) {
                rework++;
            } else {
                scrap++;
            }
        }
        vo.setTotal(records.size());
        vo.setPassCount(pass);
        vo.setReworkCount(rework);
        vo.setScrapCount(scrap);
        vo.setTypeStats(inspectionRepository.statByDefectType());
        return vo;
    }

    // ---------------- 成品架 ----------------

    /** 成品架列表：逐架填充共享质量门状态与拦截原因（已发运架位只读不倒改） */
    public List<FinishedRack> racks() {
        List<FinishedRack> racks = rackRepository.findAll();
        for (FinishedRack rack : racks) {
            boolean shipped = Dict.SHIP_SHIPPED.equals(rack.getShipStatus());
            reworkService.applyGateFields(rack, rack.getOrderNo(), rack.getBatchNo(), shipped,
                    (r, batchId, reworkNo, status) -> {
                        r.setReworkBatchId(batchId);
                        r.setReworkNo(reworkNo);
                        r.setReworkStatus(status);
                    });
        }
        return racks;
    }

    public List<RackStatDTO> rackStat() {
        return rackRepository.statByShipStatus();
    }

    @Transactional
    public FinishedRack createRack(RackRequest request) {
        if (!StringUtils.hasText(request.getRackNo())) {
            throw new BizException("架号不能为空");
        }
        if (!StringUtils.hasText(request.getOrderNo())) {
            throw new BizException("订单号不能为空");
        }
        if (rackRepository.existsByRackNo(request.getRackNo())) {
            throw new BizException("架号已存在：" + request.getRackNo());
        }
        if (request.getLengthMm() == null || request.getWidthMm() == null
                || request.getLengthMm() <= 0 || request.getWidthMm() <= 0) {
            throw new BizException("成品规格长宽必须为正整数");
        }
        if (request.getSheetCount() == null || request.getSheetCount() <= 0) {
            throw new BizException("片数必须为正整数");
        }
        FinishedRack rack = new FinishedRack();
        rack.setRackNo(request.getRackNo());
        rack.setOrderNo(request.getOrderNo());
        rack.setBatchNo(StringUtils.hasText(request.getBatchNo()) ? request.getBatchNo().trim() : null);
        rack.setGlassType(request.getGlassType());
        rack.setThicknessMm(request.getThicknessMm());
        rack.setLengthMm(request.getLengthMm());
        rack.setWidthMm(request.getWidthMm());
        rack.setSheetCount(request.getSheetCount());
        rack.setMaxLoadKg(request.getMaxLoadKg() == null
                ? BigDecimal.valueOf(3000) : request.getMaxLoadKg());
        rack.setCurrentLoadKg(BigDecimal.ZERO);
        rack.setShipStatus(Dict.SHIP_PENDING);
        // 面积是派生列：setter 会校验面积与规格片数是否吻合
        rack.setAreaSqm(BigDecimal.valueOf(
                request.getLengthMm().doubleValue() * request.getWidthMm().doubleValue()
                        * request.getSheetCount() / 1_000_000.0).setScale(2, RoundingMode.HALF_UP));
        return rackRepository.save(rack);
    }

    /**
     * 成品上架：承重超过上限不允许再上架，并给出剩余承重。
     */
    @Transactional
    public FinishedRack loadRack(Long id, RackLoadRequest request) {
        FinishedRack rack = rackRepository.findById(id)
                .orElseThrow(() -> new BizException("成品架不存在：" + id));
        if (Dict.SHIP_SHIPPED.equals(rack.getShipStatus())) {
            throw new BizException("成品架 " + rack.getRackNo() + " 已发货，不能再上架");
        }
        // 质量门：隔离 / 处理中 / 待复检批次禁止上架（不是仅前端置灰，服务端强制）
        reworkService.assertShippable(rack.getRackNo(), rack.getOrderNo(), rack.getBatchNo());
        BigDecimal weight = request == null || request.getWeightKg() == null
                ? rack.getEstimatedWeightKg() : request.getWeightKg();
        if (weight.doubleValue() <= 0) {
            throw new BizException("上架重量必须为正数");
        }
        BigDecimal remaining = rack.getRemainingLoadKg();
        if (weight.compareTo(remaining) > 0) {
            throw new BizException("成品架 " + rack.getRackNo() + " 承重已达上限：本次 "
                    + weight.setScale(2, RoundingMode.HALF_UP) + "kg，剩余承重仅 "
                    + remaining.setScale(2, RoundingMode.HALF_UP) + "kg（上限 "
                    + rack.getMaxLoadKg().setScale(2, RoundingMode.HALF_UP) + "kg）");
        }
        rack.setCurrentLoadKg(nvl(rack.getCurrentLoadKg()).add(weight));
        if (rack.getShipStatus() == null || Dict.SHIP_PENDING.equals(rack.getShipStatus())) {
            rack.setShipStatus(Dict.SHIP_READY);
        }
        return rackRepository.save(rack);
    }

    @Transactional
    public FinishedRack ship(Long id) {
        FinishedRack rack = rackRepository.findById(id)
                .orElseThrow(() -> new BizException("成品架不存在：" + id));
        if (Dict.SHIP_SHIPPED.equals(rack.getShipStatus())) {
            throw new BizException("成品架 " + rack.getRackNo() + " 已发货，请勿重复操作");
        }
        if (nvl(rack.getCurrentLoadKg()).doubleValue() <= 0) {
            throw new BizException("成品架 " + rack.getRackNo() + " 尚未上架成品，不能发货");
        }
        // 质量门：必须复检合格放行的批次才能发运；已发运数据在上一步短路，不倒改
        reworkService.assertShippable(rack.getRackNo(), rack.getOrderNo(), rack.getBatchNo());
        rack.setShipStatus(Dict.SHIP_SHIPPED);
        rack.setShippedAt(LocalDateTime.now());
        return rackRepository.save(rack);
    }

    private static BigDecimal clampPercent(BigDecimal value) {
        if (value == null) {
            return BigDecimal.valueOf(50);
        }
        double v = value.doubleValue();
        if (v < 0) {
            return BigDecimal.ZERO;
        }
        return v > 100 ? BigDecimal.valueOf(100) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
