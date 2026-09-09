package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MaterialMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialMappingRepository extends JpaRepository<MaterialMapping, Long> {

    // Used later when checking "does this material already have an active
    // mapping" - mirrors the partial unique index rule (PENDING/CONFIRMED
    // only; the DB index is the real enforcement, this is for reading).
    List<MaterialMapping> findByMaterial_MaterialId(Long materialId);
}
