package com.glass.works.repository;

import com.glass.works.domain.ReworkIdentity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkIdentityRepository extends JpaRepository<ReworkIdentity, Long> {

    List<ReworkIdentity> findByBatchId(Long batchId);

    List<ReworkIdentity> findByIdentityTypeAndIdentityValue(String identityType, String identityValue);
}
