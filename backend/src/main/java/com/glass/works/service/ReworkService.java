package com.glass.works.service;

import com.glass.works.common.BizException;
import com.glass.works.common.Dict;
import com.glass.works.domain.InspectionRecord;
import com.glass.works.domain.ReworkBatch;
import com.glass.works.domain.ReworkEvent;
import com.glass.works.domain.ReworkIdentity;
import com.glass.works.domain.ReworkMergeLog;
import com.glass.works.domain.ReworkSource;
import com.glass.works.dto.ReworkDtos.DispositionRequest;
import com.glass.works.dto.ReworkDtos.ReinspectRequest;
import com.glass.works.dto.Views.ReworkBatchVO;
import com.glass.works.dto.Views.ReworkEventVO;
import com.glass.works.dto.Views.ReworkMergeLogVO;
import com.glass.works.dto.Views.ReworkSourceVO;
import com.glass.works.dto.Views.ReworkTimelineVO;
import com.glass.works.repository.InspectionRecordRepository;
import com.glass.works.repository.ReworkBatchRepository;
import com.glass.works.repository.ReworkEventRepository;
import com.glass.works.repository.ReworkIdentityRepository;
import com.glass.works.repository.ReworkMergeLogRepository;
import com.glass.works.repository.ReworkSourceRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 返修质量闭环核心服务。
 *
 * <p>三块职责：</p>
 * <ol>
 *   <li><b>归并取舍</b>：判返修的检验记录按“批次号是实物强标识、订单号是可桥接弱标识”
 *       的规则并入返修批次；只有批次号 / 只有订单号 / 两者皆无的旧记录各有明确去向，
 *       每笔落 {@link ReworkMergeLog}，可解释、可回放。</li>
 *   <li><b>受控流转</b>：隔离 → 处理中 → 待复检 → 已放行，复检再判返修回隔离；
 *       复检提交用行级悲观写锁 + 乐观版本，双检验员并发相反结论只产生一个最终状态。</li>
 *   <li><b>质量门</b>：成品架上架 / 发运统一在此读取批次质量状态，
 *       隔离 / 处理中 / 待复检一律拦截；已发运数据不倒改。</li>
 * </ol>
 */
@Service
public class ReworkService {

    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReworkBatchRepository batchRepository;
    private final ReworkIdentityRepository identityRepository;
    private final ReworkSourceRepository sourceRepository;
    private final ReworkEventRepository eventRepository;
    private final ReworkMergeLogRepository mergeLogRepository;
    private final InspectionRecordRepository inspectionRepository;
    private final ReworkConflictRecorder conflictRecorder;

    public ReworkService(ReworkBatchRepository batchRepository,
                         ReworkIdentityRepository identityRepository,
                         ReworkSourceRepository sourceRepository,
                         ReworkEventRepository eventRepository,
                         ReworkMergeLogRepository mergeLogRepository,
                         InspectionRecordRepository inspectionRepository,
                         ReworkConflictRecorder conflictRecorder) {
        this.batchRepository = batchRepository;
        this.identityRepository = identityRepository;
        this.sourceRepository = sourceRepository;
        this.eventRepository = eventRepository;
        this.mergeLogRepository = mergeLogRepository;
        this.inspectionRepository = inspectionRepository;
        this.conflictRecorder = conflictRecorder;
    }

    // ================================================================
    // 一、归并引擎：批次号强标识 + 订单号弱桥接
    // ================================================================

    /**
     * 登记一笔判返修的检验记录，并入（或新建）返修批次。与检验保存处在同一事务内。
     * 幂等：同一检验记录重复提交不会生成两张返修单。
     */
    @Transactional
    public ReworkBatch ingest(InspectionRecord record) {
        if (record == null || record.getId() == null
                || sourceRepository.existsByInspectionId(record.getId())) {
            return record == null ? null : findBatchOfInspection(record.getId());
        }
        String batchNo = trimToNull(record.getBatchNo());
        String orderNo = trimToNull(record.getOrderNo());

        Long targetId;
        String reason;
        Long mergedFrom = null;
        String detail;

        // ① 批次号是实物强标识：批次号相同即同一实物，无论订单号 / 工序是否一致
        if (batchNo != null) {
            List<ReworkBatch> batchGroups = activeBatchesByIdentity("BATCH", batchNo);
            if (!batchGroups.isEmpty()) {
                ReworkBatch group = batchGroups.get(0);
                targetId = group.getId();
                if (orderNo != null) {
                    ensureIdentity(group, "ORDER", orderNo);
                    // 订单号同时指向另一张实物批次：拒绝归并，以批次号为准，并对拍留痕
                    List<ReworkBatch> orderGroups = activeBatchesByIdentity("ORDER", orderNo).stream()
                            .filter(g -> !g.getId().equals(group.getId())
                                    && g.getKeyBatchNo() != null
                                    && !batchNo.equals(g.getKeyBatchNo()))
                            .toList();
                    if (!orderGroups.isEmpty()) {
                        reason = Dict.MR_REFUSE_BATCH_CONFLICT;
                        detail = "批次号 " + batchNo + " 与订单号 " + orderNo
                                + " 指向不同实物批次，按批次号归入本单，拒绝与 "
                                + describeGroups(orderGroups) + " 合并";
                    } else {
                        reason = Dict.MR_SAME_BATCH;
                        detail = "批次号 " + batchNo + " 一致，跨工序归并到同一实物批次";
                    }
                } else {
                    reason = Dict.MR_SAME_BATCH;
                    detail = "批次号 " + batchNo + " 一致，归入同一实物批次";
                }
                // 放行后再次判返修：重新隔离
                if (Dict.RW_RELEASED.equals(group.getStatus())) {
                    reopen(group, record);
                }
            } else {
                // ② 新实物批次。若它的订单号恰好挂在一张仅订单号的临时单上，则桥接收编
                List<ReworkBatch> orderGroups = orderNo == null
                        ? List.of()
                        : activeBatchesByIdentity("ORDER", orderNo);
                ReworkBatch group = newBatch(batchNo, orderNo, record.getInspectedAt());
                group = batchRepository.save(group);
                if (!orderGroups.isEmpty()) {
                    List<ReworkBatch> bridgeable = orderGroups.stream()
                            .filter(g -> g.getKeyBatchNo() == null)
                            .toList();
                    for (ReworkBatch bridgeable : bridgeable) {
                        mergeInto(group, g, record.getInspectedAt());
                        mergedFrom = g.getId();
                    }
                    if (!bridgeable.isEmpty()) {
                        reason = Dict.MR_ORDER_BRIDGE;
                        detail = "以批次号 " + batchNo + " 坐实实物，桥接收编仅订单号 "
                                + orderNo + " 的临时隔离单";
                    } else {
                        reason = Dict.MR_NEW_BATCH;
                        detail = "新实物批次 " + batchNo + " 建单（订单号 " + orderNo
                                + " 已属于其他实物批次，不合并）";
                    }
                    ensureIdentity(group, "ORDER", orderNo);
                } else {
                    ensureIdentity(group, "ORDER", orderNo);
                    reason = Dict.MR_NEW_BATCH;
                    detail = "新实物批次 " + batchNo + " 建隔离单";
                }
                targetId = group.getId();
            }
        } else if (orderNo != null) {
            // ③ 只有订单号（旧记录常见）：能唯一落到一张实物批次才桥接；
            //    订单跨多张实物批次则拒绝归并，独立隔离，避免误并不同实物
            List<ReworkBatch> orderGroups = activeBatchesByIdentity("ORDER", orderNo);
            List<ReworkBatch> physical = orderGroups.stream()
                    .filter(g -> g.getKeyBatchNo() != null)
                    .toList();
            if (physical.size() == 1 && orderGroups.size() == 1) {
                ReworkBatch group = physical.get(0);
                targetId = group.getId();
                reason = Dict.MR_ORDER_MATCH;
                detail = "仅持有订单号 " + orderNo + "，唯一匹配实物批次 "
                        + group.getKeyBatchNo() + "，按订单归入";
                if (Dict.RW_RELEASED.equals(group.getStatus())) {
                    reopen(group, record);
                }
            } else if (physical.size() > 1) {
                ReworkBatch group = newBatch(null, orderNo, record.getInspectedAt());
                ensureIdentity(group, "ORDER", orderNo);
                group.setIsolationLocation("隔离区-待核");
                group = batchRepository.save(group);
                targetId = group.getId();
                reason = Dict.MR_REFUSE_ORDER_AMBIGUOUS;
                detail = "订单号 " + orderNo + " 同时出现在多个实物批次（"
                        + describeGroups(physical)
                        + "），无法判定实物，拒绝合并，先独立隔离待人工核实";
            } else {
                ReworkBatch group = newBatch(null, orderNo, record.getInspectedAt());
                ensureIdentity(group, "ORDER", orderNo);
                group.setIsolationLocation("隔离区-待核");
                group = batchRepository.save(group);
                targetId = group.getId();
                reason = Dict.MR_ORDER_ONLY;
                detail = "旧记录只有订单号 " + orderNo + "，先按订单独立隔离，待批次号坐实后桥接";
            }
        } else {
            // ④ 两者皆无的更老记录：独立成单，绝不让旧记录整体失联
            ReworkBatch group = newBatch(null, null, record.getInspectedAt());
            group.setIsolationLocation("隔离区-待核");
            group = batchRepository.save(group);
            targetId = group.getId();
            reason = Dict.MR_ISOLATED_NO_KEY;
            detail = "历史记录缺少批次号与订单号，独立隔离待核实，不与任何批次合并";
        }

        ReworkBatch target = batchRepository.findById(targetId).orElseThrow();
        attachSource(target, record, reason);
        logMerge(record, targetId, mergedFrom, reason, detail);
        return batchRepository.findById(targetId).orElseThrow();
    }

    /** 将无批次主键的临时单并入有批次号的实物单（并查集 union） */
    private void mergeInto(ReworkBatch survivor, ReworkBatch victim, LocalDateTime now) {
        for (ReworkIdentity id : identityRepository.findByBatchId(victim.getId())) {
            id.setBatchId(survivor.getId());
            identityRepository.save(id);
            if ("ORDER".equals(id.getIdentityType())) {
                appendOrderNo(survivor, id.getIdentityValue());
            }
        }
        for (ReworkSource s : sourceRepository.findByBatchIdOrderByLinkedAtAsc(victim.getId())) {
            s.setBatchId(survivor.getId());
            sourceRepository.save(s);
        }
        recalcSheets(survivor);
        victim.setStatus(Dict.RW_MERGED);
        victim.setUpdatedAt(now);
        batchRepository.save(victim);
        survivor.setUpdatedAt(now);
        batchRepository.save(survivor);
        ReworkEvent event = newEvent(survivor.getId(), Dict.EV_MERGE, null,
                victim.getStatus(), survivor.getStatus(), "系统",
                "仅订单号的临时隔离单 " + victim.getReworkNo()
                        + " 经批次号坐实后归并至本单，来源与片数已并入，原单保留可审计");
        eventRepository.save(event);
    }

    private void attachSource(ReworkBatch group, InspectionRecord record, String reason) {
        ReworkSource source = new ReworkSource();
        source.setBatchId(group.getId());
        source.setInspectionId(record.getId());
        source.setInspectNo(record.getInspectNo());
        source.setSourceProcess(record.getProcessType());
        source.setSourceBatchNo(trimToNull(record.getBatchNo()));
        source.setSourceOrderNo(trimToNull(record.getOrderNo()));
        source.setDefectType(record.getDefectType());
        source.setSheetCount(record.getSheetCount() == null ? 1 : record.getSheetCount());
        source.setInspector(record.getInspector());
        source.setRoundNo(group.getCurrentRound());
        source.setMergeReason(reason);
        source.setLinkedAt(LocalDateTime.now());
        sourceRepository.save(source);
        recalcSheets(group);
        if (trimToNull(record.getOrderNo()) != null) {
            appendOrderNo(group, record.getOrderNo());
        }
        group.setUpdatedAt(LocalDateTime.now());
        batchRepository.save(group);
    }

    private void logMerge(InspectionRecord record, Long batchId, Long mergedFrom,
                          String reason, String detail) {
        if (record == null) {
            return;
        }
        ReworkMergeLog log = new ReworkMergeLog();
        log.setInspectNo(record.getInspectNo());
        log.setBatchNo(trimToNull(record.getBatchNo()));
        log.setOrderNo(trimToNull(record.getOrderNo()));
        log.setBatchId(batchId);
        log.setMergedFromBatchId(mergedFrom);
        log.setReason(reason);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        mergeLogRepository.save(log);
    }

    // ================================================================
    // 二、受控流转：隔离 → 处理中 → 待复检 → 已放行 / 回隔离
    // ================================================================

    /** 开工处置：隔离中 → 处理中 */
    @Transactional
    public ReworkBatch start(Long id, DispositionRequest request) {
        ReworkBatch batch = lockBatch(id);
        requireStatus(batch, Dict.RW_QUARANTINED, "开工处置");
        String method = request == null ? null : trimToNull(request.getMethod());
        if (method == null) {
            throw new BizException("请填写处置方式后再开工");
        }
        String operator = operatorOf(request == null ? null : request.getOperator());
        String from = batch.getStatus();
        batch.setStatus(Dict.RW_PROCESSING);
        batch.setDispositionMethod(method);
        batch.setDispositionProgress(trimToNull(request.getProgress()) == null
                ? "已开工" : request.getProgress());
        batch.setDisposedBy(operator);
        touch(batch);
        batchRepository.save(batch);
        eventRepository.save(newEvent(id, Dict.EV_START, batch.getCurrentRound(),
                from, Dict.RW_PROCESSING, operator,
                "处置方式：" + method + "；" + nullToEmpty(batch.getDispositionProgress())));
        return batch;
    }

    /** 登记处置进度；finish=true 时 处理中 → 待复检 */
    @Transactional
    public ReworkBatch dispose(Long id, DispositionRequest request) {
        ReworkBatch batch = lockBatch(id);
        requireStatus(batch, Dict.RW_PROCESSING, "登记处置");
        if (request == null) {
            throw new BizException("处置内容不能为空");
        }
        String operator = operatorOf(request.getOperator());
        if (trimToNull(request.getMethod()) != null) {
            batch.setDispositionMethod(request.getMethod().trim());
        }
        if (trimToNull(request.getProgress()) != null) {
            batch.setDispositionProgress(request.getProgress().trim());
        }
        boolean finish = Boolean.TRUE.equals(request.getFinish());
        String from = batch.getStatus();
        if (finish) {
            if (trimToNull(batch.getDispositionMethod()) == null) {
                throw new BizException("请先填写处置方式再提交复检");
            }
            batch.setStatus(Dict.RW_INSPECTING);
            batch.setDispositionProgress(trimToNull(request.getProgress()) == null
                    ? "处置完成，待复检" : request.getProgress().trim());
        }
        batch.setDisposedBy(operator);
        touch(batch);
        batchRepository.save(batch);
        eventRepository.save(newEvent(id, Dict.EV_DISPOSE,
                batch.getCurrentRound(), from, batch.getStatus(), operator,
                (finish ? "处置完成，流转待复检；" : "处置进度更新；")
                        + nullToEmpty(batch.getDispositionMethod()) + " / "
                        + nullToEmpty(batch.getDispositionProgress())));
        return batch;
    }

    /**
     * 复检提交。行级悲观写锁串行化并发提交：
     * 先到者依据结论推进到已放行 / 回隔离；后到者若结论与当前有效状态冲突，
     * 结论不落为最终状态，而以独立事务写一条 CONFLICT 事件，接口返回明确冲突。
     */
    @Transactional
    public ReworkBatch reinspect(Long id, ReinspectRequest request) {
        if (request == null || !Dict.isValidVerdict(request.getVerdict())
                || Dict.VERDICT_SCRAP.equals(request.getVerdict())) {
            throw new BizException("复检结论只能为合格（放行）或返修（回隔离）");
        }
        ReworkBatch batch = batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("返修批次不存在：" + id));
        String inspector = operatorOf(request.getInspector());
        String verdict = request.getVerdict();

        // 状态已不是待复检：说明另一结论先一步生效，本次为并发落败方
        if (!Dict.RW_INSPECTING.equals(batch.getStatus())) {
            String existing = latestWinningVerdict(batch);
            conflictRecorder.record(batch.getId(), batch.getCurrentRound(),
                    inspector, verdict, existing,
                    "与检验员「" + lastReleaseActor(batch) + "」的结论 "
                            + Dict.verdictName(existing) + " 几乎同时提交；"
                            + Dict.verdictName(existing) + " 已先生效，本结论 "
                            + Dict.verdictName(verdict) + " 不覆盖最终状态");
            throw new ConcurrentVerdictConflictException(batch, existing, inspector, verdict);
        }

        String from = batch.getStatus();
        String detail = buildReinspectDetail(request);
        if (Dict.VERDICT_PASS.equals(verdict)) {
            // 只有合格复检才能完成复检放行
            batch.setStatus(Dict.RW_RELEASED);
            batch.setDispositionProgress("复检合格，已放行");
            batch.setReleasedAt(LocalDateTime.now());
            touch(batch);
            batchRepository.save(batch);
            eventRepository.save(newEvent(id, Dict.EV_RELEASE, batch.getCurrentRound(),
                    from, Dict.RW_RELEASED, inspector, detail));
        } else {
            // 再次判返修：回到隔离，开始下一轮
            int nextRound = (batch.getCurrentRound() == null ? 1 : batch.getCurrentRound()) + 1;
            batch.setStatus(Dict.RW_QUARANTINED);
            batch.setCurrentRound(nextRound);
            batch.setDispositionProgress("复检再判返修，第 " + nextRound + " 轮重新隔离");
            touch(batch);
            batchRepository.save(batch);
            eventRepository.save(newEvent(id, Dict.EV_REWORK_BACK, nextRound,
                    from, Dict.RW_QUARANTINED, inspector, detail));
            // 再判返修本身也是一笔可追溯来源
            attachReworkBackSource(batch, request, inspector, nextRound);
        }
        return batch;
    }

    /** 放行后又在任一工序被判返修：重新隔离（轮次 +1，留 REOPEN 事件） */
    private void reopen(ReworkBatch batch, InspectionRecord record) {
        int nextRound = (batch.getCurrentRound() == null ? 1 : batch.getCurrentRound()) + 1;
        String from = batch.getStatus();
        batch.setStatus(Dict.RW_QUARANTINED);
        batch.setCurrentRound(nextRound);
        batch.setReleasedAt(null);
        batch.setDispositionProgress("放行后在「" + Dict.workStageName(record.getProcessType())
                + "」再判返修，重新隔离");
        touch(batch);
        batchRepository.save(batch);
        eventRepository.save(newEvent(batch.getId(), Dict.EV_REOPEN, nextRound,
                from, Dict.RW_QUARANTINED, record.getInspector(),
                "检验单 " + record.getInspectNo() + " 在 "
                        + Dict.workStageName(record.getProcessType())
                        + " 再判返修，批次退出放行状态"));
    }

    private void attachReworkBackSource(ReworkBatch batch, ReinspectRequest request,
                                        String inspector, int round) {
        ReworkSource source = new ReworkSource();
        source.setBatchId(batch.getId());
        source.setInspectionId(null);
        source.setInspectNo("RW-CHK-R" + round);
        source.setSourceProcess("REINSPECT");
        source.setSourceBatchNo(batch.getKeyBatchNo());
        source.setSourceOrderNo(firstOrderNo(batch.getOrderNos()));
        source.setDefectType(trimToNull(request.getDefectType()));
        source.setSheetCount(request.getAffectedSheets() == null ? 1 : request.getAffectedSheets());
        source.setInspector(inspector);
        source.setRoundNo(round);
        source.setMergeReason(Dict.MR_SAME_BATCH);
        source.setLinkedAt(LocalDateTime.now());
        sourceRepository.save(source);
        recalcSheets(batch);
        batchRepository.save(batch);
    }

    // ================================================================
    // 三、质量门：成品架上架 / 发运统一读取同一份批次质量状态
    // ================================================================

    /**
     * 质量门校验。隔离 / 处理中 / 待复检的关联批次一律拦截；已放行可继续。
     * 已发运数据不倒改：调用方对 SHIPPED 架位应先行短路，不进入本方法。
     */
    @Transactional(readOnly = true)
    public void assertShippable(String rackNo, String orderNo, String batchNo) {
        ReworkBatch gate = matchGateBatch(orderNo, batchNo);
        if (gate != null && Dict.isOpenReworkStatus(gate.getStatus())) {
            throw new BizException("发运拦截：成品架 " + rackNo + " 关联返修批次 "
                    + gate.getReworkNo() + "（"
                    + (gate.getKeyBatchNo() == null ? "订单 " + nullToEmpty(firstOrderNo(gate.getOrderNos()))
                    : "批次 " + gate.getKeyBatchNo()) + "）当前为「"
                    + Dict.reworkStatusName(gate.getStatus())
                    + "」，须复检合格放行后方可上架 / 发运");
        }
    }

    /** 给成品架列表填充质量门只读字段；已发运架位按历史事实只读展示，不倒改 */
    @Transactional(readOnly = true)
    public <T> void applyGateFields(T rack, String orderNo, String batchNo,
                                    boolean shipped, GateSetter<T> setter) {
        if (shipped) {
            setter.set(rack, null, null, null);
            return;
        }
        ReworkBatch gate = matchGateBatch(orderNo, batchNo);
        if (gate == null) {
            setter.set(rack, null, null, null);
        } else {
            setter.set(rack, gate.getId(), gate.getReworkNo(), gate.getStatus());
        }
    }

    /** 批次号优先、订单号兜底；只认未归并（有效）批次 */
    @Transactional(readOnly = true)
    public ReworkBatch matchGateBatch(String orderNo, String batchNo) {
        String b = trimToNull(batchNo);
        String o = trimToNull(orderNo);
        if (b != null) {
            List<ReworkBatch> groups = activeBatchesByIdentity("BATCH", b);
            if (!groups.isEmpty()) {
                return groups.get(0);
            }
        }
        if (o != null) {
            List<ReworkBatch> groups = activeBatchesByIdentity("ORDER", o);
            ReworkBatch physical = groups.stream()
                    .filter(g -> g.getKeyBatchNo() != null)
                    .findFirst().orElse(null);
            return physical != null ? physical : (groups.isEmpty() ? null : groups.get(0));
        }
        return null;
    }

    /** 接口约定，避免 service 依赖 FinishedRack 具体类型 */
    @FunctionalInterface
    public interface GateSetter<T> {
        void set(T rack, Long batchId, String reworkNo, String status);
    }

    // ================================================================
    // 四、查询：看板、时间线、开放批次
    // ================================================================

    @Transactional(readOnly = true)
    public List<ReworkBatchVO> board(String status) {
        List<ReworkBatch> batches;
        if (StringUtils.hasText(status)) {
            if (!Dict.isValidReworkStatus(status)) {
                throw new BizException("返修状态非法：" + status);
            }
            batches = batchRepository.findByStatusInOrderByUpdatedAtDesc(List.of(status));
        } else {
            batches = batchRepository.findByStatusNotOrderByUpdatedAtDesc(Dict.RW_MERGED);
        }
        List<ReworkBatchVO> result = new ArrayList<>();
        for (ReworkBatch b : batches) {
            result.add(toBatchVO(b, false));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ReworkTimelineVO timeline(Long id) {
        ReworkBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("返修批次不存在：" + id));
        ReworkTimelineVO vo = new ReworkTimelineVO();
        vo.setBatch(toBatchVO(batch, true));
        vo.setSources(sourceRepository.findByBatchIdOrderByLinkedAtAsc(id).stream()
                .map(this::toSourceVO).toList());
        vo.setEvents(eventRepository.findByBatchIdOrderByEventAtAscIdAsc(id).stream()
                .map(this::toEventVO).toList());
        vo.setMergeLogs(mergeLogRepository.findByBatchIdOrderByIdAsc(id).stream()
                .map(this::toMergeLogVO).toList());
        return vo;
    }

    /** 深加工 / 检验页共享的开放返修批次（状态标签用） */
    @Transactional(readOnly = true)
    public List<ReworkBatchVO> openBatches() {
        return batchRepository.findByStatusInOrderByUpdatedAtDesc(Dict.REWORK_OPEN_STATUSES)
                .stream().map(b -> toBatchVO(b, false)).toList();
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return batchRepository.findByStatusInOrderByUpdatedAtDesc(Dict.REWORK_OPEN_STATUSES).size();
    }

    /** 按实物批次号查当前仍开放（未收口）的返修状态；已放行 / 已归并不返回 */
    @Transactional(readOnly = true)
    public Map<String, String> openStatusByBatchNo(Set<String> batchNos) {
        Map<String, String> map = new LinkedHashMap<>();
        if (batchNos == null || batchNos.isEmpty()) {
            return map;
        }
        for (String b : batchNos) {
            List<ReworkBatch> groups = activeBatchesByIdentity("BATCH", b);
            if (!groups.isEmpty() && Dict.isOpenReworkStatus(groups.get(0).getStatus())) {
                map.put(b, groups.get(0).getStatus());
            }
        }
        return map;
    }

    // ================================================================
    // 辅助
    // ================================================================

    private List<ReworkBatch> activeBatchesByIdentity(String type, String value) {
        return identityRepository.findByIdentityTypeAndIdentityValue(type, value).stream()
                .map(ReworkIdentity::getBatchId)
                .distinct()
                .map(bid -> batchRepository.findById(bid).orElse(null))
                .filter(b -> b != null && !Dict.RW_MERGED.equals(b.getStatus()))
                .sorted(Comparator.comparing(ReworkBatch::getId))
                .toList();
    }

    private ReworkBatch lockBatch(Long id) {
        return batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("返修批次不存在：" + id));
    }

    private void requireStatus(ReworkBatch batch, String expected, String action) {
        if (!expected.equals(batch.getStatus())) {
            throw new BizException("当前状态为「" + Dict.reworkStatusName(batch.getStatus())
                    + "」，不能执行" + action
                    + "；受控流转：隔离中 → 处理中 → 待复检 → 已放行");
        }
    }

    private ReworkBatch newBatch(String batchNo, String orderNo, LocalDateTime inspectedAt) {
        LocalDateTime now = LocalDateTime.now();
        ReworkBatch batch = new ReworkBatch();
        batch.setReworkNo(generateReworkNo());
        batch.setKeyBatchNo(batchNo);
        batch.setOrderNos(orderNo == null ? null : orderNo);
        batch.setIsolationLocation(defaultLocation(batchNo));
        batch.setStatus(Dict.RW_QUARANTINED);
        batch.setCurrentRound(1);
        batch.setTotalSheets(0);
        batch.setIsolatedAt(inspectedAt == null ? now : inspectedAt);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch = batchRepository.save(batch);
        ensureIdentity(batch, "BATCH", batchNo);
        eventRepository.save(newEvent(batch.getId(), Dict.EV_ISOLATE, 1,
                null, Dict.RW_QUARANTINED, "系统",
                        "检验判返修，移入 " + batch.getIsolationLocation()
                        + (batchNo == null ? "" : "（实物批次 " + batchNo + "）")));
        return batch;
    }

    private String defaultLocation(String batchNo) {
        long n = batchRepository.count() + 1;
        return "隔离区-" + String.format("%02d", (n % 20) + 1);
    }

    private String generateReworkNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int i = 0; i < 50; i++) {
            String no = "RW-" + date + "-" + String.format("%03d",
                    (int) (Math.random() * 900) + 100);
            if (!batchRepository.existsByReworkNo(no)) {
                return no;
            }
        }
        return "RW-" + System.currentTimeMillis();
    }

    private void ensureIdentity(ReworkBatch batch, String type, String value) {
        String v = trimToNull(value);
        if (v == null) {
            return;
        }
        boolean exists = identityRepository.findByBatchId(batch.getId()).stream()
                .anyMatch(i -> type.equals(i.getIdentityType()) && v.equals(i.getIdentityValue()));
        if (!exists) {
            ReworkIdentity identity = new ReworkIdentity();
            identity.setBatchId(batch.getId());
            identity.setIdentityType(type);
            identity.setIdentityValue(v);
            identity.setCreatedAt(LocalDateTime.now());
            identityRepository.save(identity);
        }
        if ("ORDER".equals(type)) {
            appendOrderNo(batch, v);
        }
    }

    private void appendOrderNo(ReworkBatch batch, String orderNo) {
        String v = trimToNull(orderNo);
        if (v == null) {
            return;
        }
        Set<String> set = new LinkedHashSet<>();
        if (batch.getOrderNos() != null) {
            for (String s : batch.getOrderNos().split(",")) {
                if (StringUtils.hasText(s)) {
                    set.add(s.trim());
                }
            }
        }
        set.add(v);
        batch.setOrderNos(String.join(",", set));
    }

    private void recalcSheets(ReworkBatch batch) {
        int total = sourceRepository.findByBatchIdOrderByLinkedAtAsc(batch.getId()).stream()
                .mapToInt(s -> s.getSheetCount() == null ? 0 : s.getSheetCount())
                .sum();
        batch.setTotalSheets(total);
    }

    private ReworkEvent newEvent(Long batchId, String type, Integer round,
                                 String from, String to, String actor, String detail) {
        ReworkEvent event = new ReworkEvent();
        event.setBatchId(batchId);
        event.setEventType(type);
        event.setRoundNo(round);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActor(actor);
        event.setDetail(detail);
        event.setEventAt(LocalDateTime.now());
        return event;
    }

    private String latestWinningVerdict(ReworkBatch batch) {
        return eventRepository.findByBatchIdOrderByEventAtAscIdAsc(batch.getId()).stream()
                .filter(e -> Dict.EV_RELEASE.equals(e.getEventType())
                        || Dict.EV_REWORK_BACK.equals(e.getEventType()))
                .reduce((a, b) -> b)
                .map(e -> Dict.EV_RELEASE.equals(e.getEventType())
                        ? Dict.VERDICT_PASS : Dict.VERDICT_REWORK)
                .orElse(Dict.RW_INSPECTING.equals(batch.getStatus())
                        ? Dict.VERDICT_REWORK : Dict.VERDICT_PASS);
    }

    private String lastReleaseActor(ReworkBatch batch) {
        return eventRepository.findByBatchIdOrderByEventAtAscIdAsc(batch.getId()).stream()
                .filter(e -> Dict.EV_RELEASE.equals(e.getEventType())
                        || Dict.EV_REWORK_BACK.equals(e.getEventType()))
                .reduce((a, b) -> b)
                .map(ReworkEvent::getActor)
                .orElse("另一检验员");
    }

    private String buildReinspectDetail(ReinspectRequest request) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(request.getDefectType())) {
            sb.append("缺陷：").append(Dict.defectTypeName(request.getDefectType()));
        }
        if (request.getDefectSizeMm() != null) {
            sb.append(sb.length() > 0 ? "；" : "").append("尺寸 ").append(request.getDefectSizeMm()).append("mm");
        }
        if (request.getAffectedSheets() != null) {
            sb.append(sb.length() > 0 ? "；" : "").append("涉及 ").append(request.getAffectedSheets()).append(" 片");
        }
        if (StringUtils.hasText(request.getNote())) {
            sb.append(sb.length() > 0 ? "；" : "").append(request.getNote().trim());
        }
        return sb.length() == 0 ? "复检结论：" + Dict.verdictName(request.getVerdict()) : sb.toString();
    }

    private ReworkBatch findBatchOfInspection(Long inspectionId) {
        return sourceRepository.findByInspectionId(inspectionId)
                .map(s -> batchRepository.findById(s.getBatchId()).orElse(null))
                .orElse(null);
    }

    private String describeGroups(List<ReworkBatch> groups) {
        return groups.stream()
                .map(g -> g.getKeyBatchNo() == null ? g.getReworkNo() : g.getKeyBatchNo())
                .reduce((a, b) -> a + "、" + b).orElse("");
    }

    private static void touch(ReworkBatch batch) {
        batch.setUpdatedAt(LocalDateTime.now());
    }

    private static String operatorOf(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "当前操作员";
    }

    private static String trimToNull(String s) {
        return s == null ? null : (s.trim().isEmpty() ? null : s.trim());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String firstOrderNo(String orderNos) {
        if (!StringUtils.hasText(orderNos)) {
            return null;
        }
        return orderNos.split(",")[0].trim();
    }

    private ReworkBatchVO toBatchVO(ReworkBatch b, boolean withSources) {
        ReworkBatchVO vo = new ReworkBatchVO();
        vo.setId(b.getId());
        vo.setReworkNo(b.getReworkNo());
        vo.setKeyBatchNo(b.getKeyBatchNo());
        vo.setOrderNos(b.getOrderNos());
        vo.setIsolationLocation(b.getIsolationLocation());
        vo.setStatus(b.getStatus());
        vo.setStatusName(b.getStatusName());
        vo.setCurrentRound(b.getCurrentRound());
        vo.setTotalSheets(b.getTotalSheets());
        vo.setDispositionMethod(b.getDispositionMethod());
        vo.setDispositionProgress(b.getDispositionProgress());
        vo.setDisposedBy(b.getDisposedBy());
        vo.setIsolatedAt(fmt(b.getIsolatedAt()));
        vo.setReleasedAt(fmt(b.getReleasedAt()));
        vo.setUpdatedAt(fmt(b.getUpdatedAt()));
        vo.setVersion(b.getVersion());
        List<ReworkSource> sources = sourceRepository.findByBatchIdOrderByLinkedAtAsc(b.getId());
        vo.setSourceCount(sources.size());
        vo.setSourceProcesses(sources.stream()
                .map(ReworkSource::getSourceProcess)
                .filter(StringUtils::hasText).distinct().toList());
        vo.setDefects(sources.stream()
                .map(ReworkSource::getDefectType)
                .filter(StringUtils::hasText).distinct().toList());
        if (withSources) {
            vo.setSources(sources.stream().map(this::toSourceVO).toList());
        }
        return vo;
    }

    private ReworkSourceVO toSourceVO(ReworkSource s) {
        ReworkSourceVO vo = new ReworkSourceVO();
        vo.setId(s.getId());
        vo.setInspectionId(s.getInspectionId());
        vo.setInspectNo(s.getInspectNo());
        vo.setSourceProcess(s.getSourceProcess());
        vo.setSourceProcessName("REINSPECT".equals(s.getSourceProcess())
                ? "复检" : Dict.workStageName(s.getSourceProcess()));
        vo.setSourceBatchNo(s.getSourceBatchNo());
        vo.setSourceOrderNo(s.getSourceOrderNo());
        vo.setDefectType(s.getDefectType());
        vo.setDefectTypeName(Dict.defectTypeName(s.getDefectType()));
        vo.setSheetCount(s.getSheetCount());
        vo.setInspector(s.getInspector());
        vo.setRoundNo(s.getRoundNo());
        vo.setMergeReason(s.getMergeReason());
        vo.setMergeReasonName(Dict.mergeReasonName(s.getMergeReason()));
        vo.setLinkedAt(fmt(s.getLinkedAt()));
        return vo;
    }

    private ReworkEventVO toEventVO(ReworkEvent e) {
        ReworkEventVO vo = new ReworkEventVO();
        vo.setId(e.getId());
        vo.setEventType(e.getEventType());
        vo.setEventTypeName(Dict.reworkEventName(e.getEventType()));
        vo.setRoundNo(e.getRoundNo());
        vo.setFromStatus(e.getFromStatus());
        vo.setFromStatusName(Dict.reworkStatusName(e.getFromStatus()));
        vo.setToStatus(e.getToStatus());
        vo.setToStatusName(Dict.reworkStatusName(e.getToStatus()));
        vo.setActor(e.getActor());
        vo.setDetail(e.getDetail());
        vo.setConflictInspector(e.getConflictInspector());
        vo.setConflictVerdict(e.getConflictVerdict());
        vo.setConflictVerdictName(Dict.verdictName(e.getConflictVerdict()));
        vo.setWinningVerdict(e.getWinningVerdict());
        vo.setWinningVerdictName(Dict.verdictName(e.getWinningVerdict()));
        vo.setEventAt(fmt(e.getEventAt()));
        return vo;
    }

    private ReworkMergeLogVO toMergeLogVO(ReworkMergeLog l) {
        ReworkMergeLogVO vo = new ReworkMergeLogVO();
        vo.setId(l.getId());
        vo.setInspectNo(l.getInspectNo());
        vo.setBatchNo(l.getBatchNo());
        vo.setOrderNo(l.getOrderNo());
        vo.setMergedFromBatchId(l.getMergedFromBatchId());
        vo.setReason(l.getReason());
        vo.setReasonName(Dict.mergeReasonName(l.getReason()));
        vo.setDetail(l.getDetail());
        vo.setCreatedAt(fmt(l.getCreatedAt()));
        return vo;
    }

    static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(DT);
    }

    /** 并发复检冲突：带上下文，供控制器翻译成 409 明确冲突响应 */
    public static class ConcurrentVerdictConflictException extends BizException {
        private final Long batchId;
        private final String winningVerdict;
        private final String conflictInspector;
        private final String conflictVerdict;

        public ConcurrentVerdictConflictException(ReworkBatch batch, String winningVerdict,
                                                  String conflictInspector, String conflictVerdict) {
            super("复检结论冲突：返修批次 " + batch.getReworkNo() + " 的有效最终状态已为「"
                    + Dict.reworkStatusName(batch.getStatus()) + "」（结论："
                    + Dict.verdictName(winningVerdict) + "），检验员 " + conflictInspector
                    + " 提交的「" + Dict.verdictName(conflictVerdict)
                    + "」未生效，已记入冲突审计");
            this.batchId = batch.getId();
            this.winningVerdict = winningVerdict;
            this.conflictInspector = conflictInspector;
            this.conflictVerdict = conflictVerdict;
        }

        public Long getBatchId() {
            return batchId;
        }

        public String getWinningVerdict() {
            return winningVerdict;
        }

        public String getConflictInspector() {
            return conflictInspector;
        }

        public String getConflictVerdict() {
            return conflictVerdict;
        }
    }
}
