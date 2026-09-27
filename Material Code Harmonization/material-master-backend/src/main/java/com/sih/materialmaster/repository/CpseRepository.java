package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.Cpse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CpseRepository extends JpaRepository<Cpse, Long> {
    Optional<Cpse> findByNameIgnoreCase(String name);
}
