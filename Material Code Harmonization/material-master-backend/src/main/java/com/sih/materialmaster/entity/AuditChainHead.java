package com.sih.materialmaster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Single-row anchor entity for serialized head-locking and cryptographic hash chain verification.
 * Matches V6__audit_chain_head.sql and is managed through Flyway and PostgreSQL.
 */
@Entity
@Table(name = "audit_chain_head")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainHead {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(name = "head_hash", nullable = false, length = 64)
    private String headHash;
}
