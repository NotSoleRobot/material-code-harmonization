package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.HarmonizationJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HarmonizationJobRepository extends JpaRepository<HarmonizationJob, Long> {

    List<HarmonizationJob> findByUser_UserIdOrderByCreatedAtDesc(Long userId);

    Page<HarmonizationJob> findByCpse_CpseIdOrderByCreatedAtDesc(Long cpseId, Pageable pageable);

    Page<HarmonizationJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
