package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDto {
    private long totalMaterials;
    private long totalUniqueGroups;
    private long pendingReviewCount;
    private long confirmedCount;
    private long rejectedCount;
    private long unmatchedCount;
    private long totalCpses;
    private double deduplicationRate;
    private double estimatedSavingsInrLakhs;
    private SavingsBreakdownDto savingsBreakdown;
    private List<CpseStatDto> cpseBreakdown;
    private Map<String, Long> categoryDistribution;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpseStatDto {
        private String cpseName;
        private long materialCount;
        private long mappedCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SavingsBreakdownDto {
        private double directPriceArbitrageLakhs;
        private double inventoryHoldingAvoidanceLakhs;
        private double adminDataCleanupAvoidanceLakhs;
        private double totalLakhs;
        private double inventoryCarryingRatePct;
        private long pricedDuplicateItems;
    }
}
