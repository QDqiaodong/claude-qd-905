package com.glass.works.repository;

import com.glass.works.domain.ReworkSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkSourceRepository extends JpaRepository<ReworkSource, Long> {

    List<ReworkSource> findByBatchIdOrderByLinkedAtAsc(Long batchId);

    /** 一条检验单只能归属于一张返修批次（复检再判返修会产生新的来源行） */
    Optional<ReworkSource> findByInspectionId(Long inspectionId);

    boolean existsByInspectionId(Long inspectionId);
}
