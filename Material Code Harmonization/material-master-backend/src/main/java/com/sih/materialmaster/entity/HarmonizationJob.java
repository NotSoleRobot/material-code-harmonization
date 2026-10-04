package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "harmonization_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HarmonizationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cpse_id")
    private Cpse cpse;

    @Column(name = "total_items", nullable = false)
    private Integer totalItems = 0;

    @Column(name = "processed_items", nullable = false)
    private Integer processedItems = 0;

    @Column(name = "imported_items", nullable = false)
    private Integer importedItems = 0;

    @Column(name = "skipped_items", nullable = false)
    private Integer skippedItems = 0;

    @Column(name = "auto_harmonized", nullable = false)
    private Integer autoHarmonized = 0;

    @Column(name = "pending_review", nullable = false)
    private Integer pendingReview = 0;

    @Column(name = "distinct_materials", nullable = false)
    private Integer distinctMaterials = 0;

    // QUEUED | PROCESSING_INGESTION | HARMONIZING | IN_PROGRESS | COMPLETED | FAILED
    @Column(name = "status", nullable = false, length = 30)
    private String status = "IN_PROGRESS";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "diagnostics", columnDefinition = "TEXT")
    private String diagnostics;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "IN_PROGRESS";
        }
    }
}
