package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MatchCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchCandidateRepository extends JpaRepository<MatchCandidate, Long> {
    void deleteByMaterial_MaterialId(Long materialId);
}
