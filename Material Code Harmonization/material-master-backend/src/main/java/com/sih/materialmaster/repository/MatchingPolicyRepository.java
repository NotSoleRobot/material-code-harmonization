package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MatchingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchingPolicyRepository extends JpaRepository<MatchingPolicy, Long> {
    Optional<MatchingPolicy> findByCategory_CategoryId(Long categoryId);
}
