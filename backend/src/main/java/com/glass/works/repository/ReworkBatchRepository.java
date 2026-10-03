package com.glass.works.repository;

import com.glass.works.domain.ReworkBatch;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReworkBatchRepository extends JpaRepository<ReworkBatch, Long> {

    /** 复检提交走悲观写锁：两名检验员几乎同时提交相反结论时在此串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ReworkBatch b where b.id = :id")
    Optional<ReworkBatch> findByIdForUpdate(@Param("id") Long id);

    List<ReworkBatch> findByStatusInOrderByUpdatedAtDesc(List<String> statuses);

    List<ReworkBatch> findByStatusNotOrderByUpdatedAtDesc(String status);

    List<ReworkBatch> findByKeyBatchNoAndStatusNot(String keyBatchNo, String status);

    boolean existsByReworkNo(String reworkNo);
}
