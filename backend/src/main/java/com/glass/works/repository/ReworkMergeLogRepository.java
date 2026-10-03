package com.glass.works.repository;

import com.glass.works.domain.ReworkMergeLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkMergeLogRepository extends JpaRepository<ReworkMergeLog, Long> {

    List<ReworkMergeLog> findByBatchIdOrderByIdAsc(Long batchId);
}
