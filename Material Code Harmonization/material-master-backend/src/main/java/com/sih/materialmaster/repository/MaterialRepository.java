package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    // Used later for CPSE-scoped access (Section 6: CPSE Operators can only
    // see their own org's raw material records).
    List<Material> findByCpse_CpseId(Long cpseId);
}
