package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.ProcurementAssumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcurementAssumptionRepository extends JpaRepository<ProcurementAssumption, Long> {

    Optional<ProcurementAssumption> findByKey(String key);
}
