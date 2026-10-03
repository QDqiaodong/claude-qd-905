package com.glass.works.service;

import com.glass.works.common.Dict;
import com.glass.works.domain.ReworkBatch;
import com.glass.works.domain.ReworkEvent;
import com.glass.works.domain.ReworkIdentity;
import com.glass.works.dto.ReworkDtos.DispositionRequest;
import com.glass.works.dto.ReworkDtos.ReinspectRequest;
import com.glass.works.repository.InspectionRecordRepository;
import com.glass.works.repository.ReworkBatchRepository;
import com.glass.works.repository.ReworkEventRepository;
import com.glass.works.repository.ReworkIdentityRepository;
import com.glass.works.domain.InspectionRecord;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动收口：把历史「判返修」检验记录全部喂给归并引擎形成返修隔离记录
 * （只有批次号 / 只有订单号 / 两者皆无的旧记录在此体现取舍），
 * 并把一张批次推进到处理中、一张推进到待复检、一张走完复检放行，
 * 让工作区四个状态都有待办可演示。全部步骤幂等，重复启动不倒改。
 */
@Component
@Order(20)
public class ReworkBootstrap implements ApplicationRunner {

    private final InspectionRecordRepository inspectionRepository;
    private final ReworkBatchRepository batchRepository;
    private final ReworkIdentityRepository identityRepository;
    private final ReworkEventRepository eventRepository;
    private final ReworkService reworkService;

    public ReworkBootstrap(InspectionRecordRepository inspectionRepository,
                           ReworkBatchRepository batchRepository,
                           ReworkIdentityRepository identityRepository,
                           ReworkEventRepository eventRepository,
                           @Lazy ReworkService reworkService) {
        this.inspectionRepository = inspectionRepository;
        this.batchRepository = batchRepository;
        this.identityRepository = identityRepository;
        this.eventRepository = eventRepository;
        this.reworkService = reworkService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1) 历史判返修记录按检验单顺序归并（幂等：已挂来源的跳过）
        List<InspectionRecord> reworkRecords = inspectionRepository.findAll().stream()
                .filter(r -> Dict.VERDICT_REWORK.equals(r.getVerdict()))
                .sorted(Comparator.comparing(InspectionRecord::getId))
                .toList();
        for (InspectionRecord record : reworkRecords) {
            reworkService.ingest(record);
        }
        if (batchRepository.count() == 0) {
            return;
        }

        // 2) 预置闭环演示状态（仅推进尚无 START 事件的隔离批次，幂等不倒改）
        ReworkBatch processingTarget = findQuietBatch("YP-2402-02");
        if (processingTarget != null) {
            DispositionRequest start = new DispositionRequest();
            start.setMethod("重新清洗干燥并更换分子筛");
            start.setProgress("已拆框，正在二次合片");
            start.setOperator("许静");
            reworkService.start(processingTarget.getId(), start);
        }

        ReworkBatch inspectingTarget = findQuietBatch("YP-2403-03");
        if (inspectingTarget != null) {
            Long id = inspectingTarget.getId();
            DispositionRequest start = new DispositionRequest();
            start.setMethod("复磨圆边过渡段");
            start.setProgress("复磨完成");
            start.setOperator("李娜");
            reworkService.start(id, start);
            DispositionRequest finish = new DispositionRequest();
            finish.setMethod("复磨圆边过渡段");
            finish.setProgress("复磨完成，已清洗待复检");
            finish.setOperator("李娜");
            finish.setFinish(true);
            reworkService.dispose(id, finish);
        }

        ReworkBatch releasedTarget = findQuietBatch("YP-2402-01");
        if (releasedTarget != null) {
            Long id = releasedTarget.getId();
            DispositionRequest start = new DispositionRequest();
            start.setMethod("膜面划伤返修重镀");
            start.setProgress("已重镀");
            start.setOperator("周涛");
            reworkService.start(id, start);
            DispositionRequest finish = new DispositionRequest();
            finish.setProgress("重镀完成，待复检");
            finish.setOperator("周涛");
            finish.setFinish(true);
            reworkService.dispose(id, finish);
            ReinspectRequest pass = new ReinspectRequest();
            pass.setVerdict(Dict.VERDICT_PASS);
            pass.setInspector("何斌");
            pass.setNote("复检辐射率与外观合格");
            reworkService.reinspect(id, pass);
        }
    }

    /** 按实物批次号找到仍在隔离、且尚未有任何流转事件的批次（仅这种批次允许预置演示） */
    private ReworkBatch findQuietBatch(String keyBatchNo) {
        List<ReworkBatch> groups = identityRepository
                .findByIdentityTypeAndIdentityValue("BATCH", keyBatchNo).stream()
                .map(ReworkIdentity::getBatchId)
                .distinct()
                .map(bid -> batchRepository.findById(bid).orElse(null))
                .filter(b -> b != null && Dict.RW_QUARANTINED.equals(b.getStatus()))
                .toList();
        for (ReworkBatch batch : groups) {
            boolean hasTransition = eventRepository
                    .findByBatchIdOrderByEventAtAscIdAsc(batch.getId()).stream()
                    .anyMatch(e -> Dict.EV_START.equals(e.getEventType())
                            || Dict.EV_RELEASE.equals(e.getEventType())
                            || Dict.EV_MERGE.equals(e.getEventType()));
            if (!hasTransition) {
                return batch;
            }
        }
        return null;
    }
}
