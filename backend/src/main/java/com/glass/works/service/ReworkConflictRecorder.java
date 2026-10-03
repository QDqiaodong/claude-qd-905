package com.glass.works.service;

import com.glass.works.common.Dict;
import com.glass.works.domain.ReworkBatch;
import com.glass.works.domain.ReworkEvent;
import com.glass.works.repository.ReworkBatchRepository;
import com.glass.works.repository.ReworkEventRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 并发复检冲突留痕：以 REQUIRES_NEW 独立事务写 CONFLICT 事件。
 * 落败方的提交随后回滚，但其相反结论仍保留在批次时间线，全过程可审计。
 */
@Component
public class ReworkConflictRecorder {

    private final ReworkEventRepository eventRepository;
    private final ReworkBatchRepository batchRepository;

    public ReworkConflictRecorder(ReworkEventRepository eventRepository,
                                  ReworkBatchRepository batchRepository) {
        this.eventRepository = eventRepository;
        this.batchRepository = batchRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long batchId, Integer round, String conflictInspector,
                       String conflictVerdict, String winningVerdict, String detail) {
        String currentStatus = batchRepository.findById(batchId)
                .map(ReworkBatch::getStatus).orElse(null);
        ReworkEvent event = new ReworkEvent();
        event.setBatchId(batchId);
        event.setEventType(Dict.EV_CONFLICT);
        event.setRoundNo(round);
        event.setFromStatus(Dict.RW_INSPECTING);
        event.setToStatus(currentStatus);
        event.setActor(conflictInspector);
        event.setDetail(detail);
        event.setConflictInspector(conflictInspector);
        event.setConflictVerdict(conflictVerdict);
        event.setWinningVerdict(winningVerdict);
        event.setEventAt(LocalDateTime.now());
        eventRepository.save(event);
    }
}
