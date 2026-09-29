package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import jakarta.persistence.EntityManager;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class HarmonizationJobService {

    private static final Logger log = LoggerFactory.getLogger(HarmonizationJobService.class);

    private final HarmonizationJobRepository jobRepository;
    private final HarmonizationService harmonizationService;
    private final MaterialRepository materialRepository;
    private final CpseRepository cpseRepository;
    private final UserRepository userRepository;
    private final MaterialCategoryService categoryService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final Executor harmonizationExecutor;

    private static final int INGEST_BATCH_SIZE = 500;
    private static final int HARMONIZATION_BATCH_SIZE = 150;

    public HarmonizationJobService(HarmonizationJobRepository jobRepository,
                                  HarmonizationService harmonizationService,
                                  MaterialRepository materialRepository,
                                  CpseRepository cpseRepository,
                                  UserRepository userRepository,
                                  MaterialCategoryService categoryService,
                                  AuditService auditService,
                                  TransactionTemplate transactionTemplate,
                                  EntityManager entityManager,
                                  @Qualifier("harmonizationTaskExecutor") Executor harmonizationExecutor) {
        this.jobRepository = jobRepository;
        this.harmonizationService = harmonizationService;
        this.materialRepository = materialRepository;
        this.cpseRepository = cpseRepository;
        this.userRepository = userRepository;
        this.categoryService = categoryService;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.harmonizationExecutor = harmonizationExecutor;
    }

    public HarmonizationJob createJob(User user, int totalItems) {
        HarmonizationJob job = new HarmonizationJob();
        job.setUser(user);
        job.setCpse(user != null ? user.getCpse() : null);
        job.setTotalItems(totalItems);
        job.setProcessedItems(0);
        job.setAutoHarmonized(0);
        job.setPendingReview(0);
        job.setDistinctMaterials(0);
        job.setStatus("IN_PROGRESS");
        job.setCreatedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    public HarmonizationJob createIngestionJob(User user) {
        HarmonizationJob job = createJob(user, 0);
        job.setStatus("QUEUED");
        job.setImportedItems(0);
        job.setSkippedItems(0);
        return jobRepository.save(job);
    }

    @Async("taskExecutor")
    public void processCsvAsync(Long jobId, Path spoolFile, Long operatorCpseId,
                                Long userId, boolean autoHarmonize) {
        int total = 0;
        int imported = 0;
        int skipped = 0;
        List<CompletableFuture<BatchStats>> harmonizationTasks = new ArrayList<>();
        try {
            int expectedTotal = countCsvRecords(spoolFile);
            try (BufferedReader reader = Files.newBufferedReader(spoolFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true)
                     .build().parse(reader)) {
            updateIngestionProgress(jobId, "PROCESSING_INGESTION", expectedTotal, 0, 0, 0);
            List<IngestionRow> batch = new ArrayList<>(INGEST_BATCH_SIZE);
            for (CSVRecord record : parser) {
                total++;
                batch.add(toIngestionRow(record));
                if (batch.size() == INGEST_BATCH_SIZE) {
                    PersistResult result = persistBatch(batch, operatorCpseId);
                    imported += result.materialIds().size();
                    skipped += result.skipped();
                    dispatchHarmonization(result.materialIds(), autoHarmonize, harmonizationTasks);
                    updateIngestionProgress(jobId, "PROCESSING_INGESTION", expectedTotal, total, imported, skipped);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                PersistResult result = persistBatch(batch, operatorCpseId);
                imported += result.materialIds().size();
                skipped += result.skipped();
                dispatchHarmonization(result.materialIds(), autoHarmonize, harmonizationTasks);
            }
            updateIngestionProgress(jobId, autoHarmonize ? "HARMONIZING" : "COMPLETED",
                    expectedTotal, total, imported, skipped);

            BatchStats combined = new BatchStats(0, 0, 0, 0, null);
            for (CompletableFuture<BatchStats> task : harmonizationTasks) {
                combined = combined.add(task.join());
            }
            finishIngestionJob(jobId, total, imported, skipped, combined, autoHarmonize);
            User user = userRepository.findById(userId).orElse(null);
            auditService.logEvent(user, "BULK_INGEST", "MATERIAL", 0L, null,
                    "Imported " + imported + " rows from CSV (" + skipped + " skipped)");
            }
        } catch (Exception ex) {
            log.error("CSV ingestion job {} failed", jobId, ex);
            failJob(jobId, ex.getMessage());
        } finally {
            try {
                Files.deleteIfExists(spoolFile);
            } catch (Exception cleanupError) {
                log.warn("Could not delete ingestion spool file {}: {}", spoolFile, cleanupError.getMessage());
            }
        }
    }

    private void dispatchHarmonization(List<Long> materialIds, boolean autoHarmonize,
                                       List<CompletableFuture<BatchStats>> tasks) {
        if (!autoHarmonize) return;
        for (int start = 0; start < materialIds.size(); start += HARMONIZATION_BATCH_SIZE) {
            List<Long> ids = List.copyOf(materialIds.subList(
                    start, Math.min(start + HARMONIZATION_BATCH_SIZE, materialIds.size())));
            tasks.add(CompletableFuture.supplyAsync(() -> harmonizeBatch(ids), harmonizationExecutor));
        }
    }

    private BatchStats harmonizeBatch(List<Long> materialIds) {
        int auto = 0;
        int review = 0;
        int distinct = 0;
        int failures = 0;
        String firstFailure = null;
        try {
            harmonizationService.extractAttributesForMaterials(materialIds);
            Map<Long, com.sih.materialmaster.dto.FindMatchesResponse> matches =
                    harmonizationService.prepareBatchMatches(materialIds);
            for (Long materialId : materialIds) {
                try {
                    var result = harmonizationService.harmonizeMaterial(materialId, matches.get(materialId));
                    if ("HIGH".equalsIgnoreCase(result.getConfidenceTier())) auto++;
                    else if ("MEDIUM".equalsIgnoreCase(result.getConfidenceTier())) review++;
                    else distinct++;
                } catch (Exception ex) {
                    failures++;
                    if (firstFailure == null) firstFailure = "Material " + materialId + ": " + ex.getMessage();
                }
            }
        } catch (Exception ex) {
            failures = materialIds.size();
            firstFailure = ex.getMessage();
        }
        return new BatchStats(auto, review, distinct, failures, firstFailure);
    }

    private PersistResult persistBatch(List<IngestionRow> rows, Long operatorCpseId) {
        PersistResult result = transactionTemplate.execute(status -> {
            List<Long> ids = new ArrayList<>(rows.size());
            int rejected = 0;
            Cpse operatorCpse = operatorCpseId == null ? null
                    : cpseRepository.findById(operatorCpseId).orElse(null);
            for (IngestionRow row : rows) {
                if (row.description().isBlank() || row.materialCode().isBlank() || row.category().isBlank()) {
                    rejected++;
                    continue;
                }
                Cpse cpse = operatorCpse;
                if (cpse == null) {
                    cpse = cpseRepository.findByNameIgnoreCase(
                            row.cpseName().isBlank() ? "GENERAL" : row.cpseName()).orElse(null);
                }
                if (cpse == null) {
                    rejected++;
                    continue;
                }
                Material material = materialRepository
                        .findByCpse_CpseIdAndCpseMaterialCode(cpse.getCpseId(), row.materialCode())
                        .orElseGet(Material::new);
                material.setCpse(cpse);
                material.setCpseMaterialCode(row.materialCode());
                material.setDescription(row.description());
                material.setSpecification(row.specification());
                material.setUnitOfMeasure(row.uom().isBlank() ? "NOS" : row.uom().toUpperCase());
                BigDecimal nominalPrice = parsePrice(row.price());
                if (nominalPrice != null) material.setNominalPrice(nominalPrice);
                material.setCategory(categoryService.resolveOpenDomain(row.category()));
                ids.add(materialRepository.save(material).getMaterialId());
            }
            entityManager.flush();
            entityManager.clear();
            return new PersistResult(ids, rejected);
        });
        if (result == null) throw new IllegalStateException("CSV batch transaction returned no result");
        return result;
    }

    private IngestionRow toIngestionRow(CSVRecord record) {
        return new IngestionRow(
                value(record, "cpse_name", 0), value(record, "cpse_material_code", 1),
                value(record, "description", 2), value(record, "specification", 3),
                value(record, "unit_of_measure", 4), value(record, "category", 5),
                value(record, "nominal_price", 6));
    }

    private String value(CSVRecord record, String header, int fallbackIndex) {
        String value = record.isMapped(header) ? record.get(header)
                : (record.size() > fallbackIndex ? record.get(fallbackIndex) : "");
        return value == null ? "" : value.trim();
    }

    private BigDecimal parsePrice(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String cleaned = value.replaceAll("[^0-9.-]", "");
            return cleaned.isBlank() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int countCsvRecords(Path spoolFile) throws java.io.IOException {
        try (BufferedReader reader = Files.newBufferedReader(spoolFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true)
                     .build().parse(reader)) {
            int count = 0;
            for (CSVRecord ignored : parser) count++;
            return count;
        }
    }

    private void updateIngestionProgress(Long jobId, String status, int total, int processed,
                                         int imported, int skipped) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setTotalItems(total);
            job.setProcessedItems(processed);
            job.setImportedItems(imported);
            job.setSkippedItems(skipped);
            jobRepository.save(job);
        });
    }

    private void finishIngestionJob(Long jobId, int total, int imported, int skipped,
                                    BatchStats stats, boolean autoHarmonize) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setTotalItems(total);
            job.setProcessedItems(total);
            job.setImportedItems(imported);
            job.setSkippedItems(skipped);
            job.setAutoHarmonized(stats.auto());
            job.setPendingReview(stats.review());
            job.setDistinctMaterials(autoHarmonize ? stats.distinct() : imported);
            job.setStatus(stats.failures() == 0 ? "COMPLETED" : "FAILED");
            if (stats.failures() > 0) {
                job.setErrorMessage(stats.failures() + " material(s) failed. First failure: " + stats.firstFailure());
            }
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private void failJob(Long jobId, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus("FAILED");
            job.setErrorMessage(message == null ? "Background ingestion failed" : message);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    private record IngestionRow(String cpseName, String materialCode, String description,
                                String specification, String uom, String category, String price) {}
    private record PersistResult(List<Long> materialIds, int skipped) {}
    private record BatchStats(int auto, int review, int distinct, int failures, String firstFailure) {
        BatchStats add(BatchStats other) {
            return new BatchStats(auto + other.auto, review + other.review, distinct + other.distinct,
                    failures + other.failures, firstFailure != null ? firstFailure : other.firstFailure);
        }
    }

    @Async("harmonizationTaskExecutor")
    public void processAsync(Long jobId, List<Long> materialIds) {
        try {
            int auto = 0;
            int review = 0;
            int distinct = 0;
            int failures = 0;
            String firstFailure = null;

            // Matching cost grows with both query and candidate counts. Keep each
            // request bounded so the worker remains responsive on realistic files.
            final int batchSize = 10;
            for (int start = 0; start < materialIds.size(); start += batchSize) {
                    int end = Math.min(start + batchSize, materialIds.size());
                    List<Long> batchIds = materialIds.subList(start, end);
                    harmonizationService.extractAttributesForMaterials(batchIds);
                    Map<Long, com.sih.materialmaster.dto.FindMatchesResponse> batchMatches =
                            harmonizationService.prepareBatchMatches(batchIds);

                    for (int offset = 0; offset < batchIds.size(); offset++) {
                        int i = start + offset;
                        Long matId = batchIds.get(offset);
                        try {
                            var res = harmonizationService.harmonizeMaterial(matId, batchMatches.get(matId));
                            if ("HIGH".equalsIgnoreCase(res.getConfidenceTier())) {
                                auto++;
                            } else if ("MEDIUM".equalsIgnoreCase(res.getConfidenceTier())) {
                                review++;
                            } else {
                                distinct++;
                            }
                        } catch (Exception ex) {
                            log.warn("Harmonization failed for item {}: {}", matId, ex.getMessage());
                            failures++;
                            if (firstFailure == null) {
                                firstFailure = "Material " + matId + ": " + ex.getMessage();
                            }
                        }

                        if ((i + 1) % 5 == 0 || (i + 1) == materialIds.size()) {
                            HarmonizationJob job = jobRepository.findById(jobId).orElse(null);
                            if (job != null) {
                                job.setProcessedItems(i + 1);
                                job.setAutoHarmonized(auto);
                                job.setPendingReview(review);
                                job.setDistinctMaterials(distinct);
                                jobRepository.save(job);
                            }
                        }
                    }
                }

            HarmonizationJob job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setProcessedItems(materialIds.size());
                job.setAutoHarmonized(auto);
                job.setPendingReview(review);
                job.setDistinctMaterials(distinct);
                job.setStatus(failures == 0 ? "COMPLETED" : "FAILED");
                if (failures > 0) {
                    job.setErrorMessage(failures + " material(s) failed. First failure: " + firstFailure);
                }
                job.setCompletedAt(LocalDateTime.now());
                jobRepository.save(job);
            }
        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            HarmonizationJob job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus("FAILED");
                job.setErrorMessage(e.getMessage());
                job.setCompletedAt(LocalDateTime.now());
                jobRepository.save(job);
            }
        }
    }

    public HarmonizationJob getJob(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job found with ID: " + jobId));
    }
}
