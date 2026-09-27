package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.AuditTrail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface AuditTrailRepository extends JpaRepository<AuditTrail, Long> {

    Optional<AuditTrail> findTop1ByOrderByAuditIdDesc();

    List<AuditTrail> findAllByOrderByAuditIdAsc();

    Page<AuditTrail> findAllByOrderByTimestampDesc(Pageable pageable);

    Page<AuditTrail> findByActionOrderByTimestampDesc(String action, Pageable pageable);

    List<AuditTrail> findTop500ByOrderByTimestampDesc();

    List<AuditTrail> findByActionOrderByTimestampDesc(String action);

    List<AuditTrail> findTop500ByEntityTypeInOrderByTimestampDesc(Collection<String> entityTypes);

    List<AuditTrail> findByActionAndEntityTypeInOrderByTimestampDesc(
            String action, Collection<String> entityTypes);

    List<AuditTrail> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, Long entityId);
}
