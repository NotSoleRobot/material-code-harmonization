package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.HarmonizationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class HarmonizationJobService {

    private static final Logger log = LoggerFactory.getLogger(HarmonizationJobService.class);

    private final HarmonizationJobRepository jobRepository;
    private final HarmonizationService harmonizationService;

    public HarmonizationJobService(HarmonizationJobRepository jobRepository,
                                  HarmonizationService harmonizationService) {
        this.jobRepository = jobRepository;
        this.harmonizationService = harmonizationService;
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
