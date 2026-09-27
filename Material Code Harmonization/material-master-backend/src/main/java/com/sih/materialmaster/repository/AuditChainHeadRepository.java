package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.AuditChainHead;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AuditChainHead a WHERE a.id = :id")
    Optional<AuditChainHead> findByIdForUpdate(@Param("id") Integer id);
}
