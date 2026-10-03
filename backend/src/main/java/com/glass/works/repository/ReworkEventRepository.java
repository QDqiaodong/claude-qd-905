package com.glass.works.repository;

import com.glass.works.domain.ReworkEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkEventRepository extends JpaRepository<ReworkEvent, Long> {

    List<ReworkEvent> findByBatchIdOrderByEventAtAscIdAsc(Long batchId);

    long countByBatchIdAndEventType(Long batchId, String eventType);
}
