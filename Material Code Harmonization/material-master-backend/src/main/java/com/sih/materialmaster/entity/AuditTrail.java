package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_trail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    // e.g. "MATERIAL_GROUP", "MATERIAL_MAPPING" - which table entityId points into.
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    // DELIBERATE polymorphic reference: a plain Long, not a JPA @ManyToOne,
    // because a single FK can't conditionally point at different tables
    // depending on entityType. This is validated in Spring Boot service code
    // only (whitelist allowed entityType values; only ever write an entityId
    // taken from a record just confirmed to exist) - the DB physically cannot
    // enforce this, so there is no equivalent schema.sql fix here, unlike
    // MaterialMapping's partial index or User's CHECK constraint.
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        this.timestamp = LocalDateTime.now();
    }
}
