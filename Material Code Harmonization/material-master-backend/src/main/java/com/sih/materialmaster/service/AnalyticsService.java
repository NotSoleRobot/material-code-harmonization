package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.DashboardStatsDto;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class AnalyticsService {

    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository groupRepository;
    private final MaterialMappingRepository mappingRepository;
    private final CpseRepository cpseRepository;
    private final ProcurementAssumptionRepository assumptionRepository;

    public AnalyticsService(MaterialRepository materialRepository,
                            MaterialGroupRepository groupRepository,
                            MaterialMappingRepository mappingRepository,
                            CpseRepository cpseRepository,
                            ProcurementAssumptionRepository assumptionRepository) {
        this.materialRepository = materialRepository;
        this.groupRepository = groupRepository;
        this.mappingRepository = mappingRepository;
        this.cpseRepository = cpseRepository;
        this.assumptionRepository = assumptionRepository;
    }

    /**
     * FR10: Computes mathematically defensible deduplication rate and ROI analytics (W6.1, BUG-17).
     *
     * Formula:
     *   duplicates_eliminated = Σ over ACTIVE groups (confirmed_members - 1)
     *   dedup_rate = (duplicates_eliminated / materials_reviewed) * 100
     */
    @Transactional(readOnly = true)
    public DashboardStatsDto getDashboardStats() {
        long totalMaterials = materialRepository.count();
        long totalGroups = groupRepository.count();
        long pendingCount = mappingRepository.countByStatus("PENDING");
        long confirmedCount = mappingRepository.countByStatus("CONFIRMED");
        long rejectedCount = mappingRepository.countByStatus("REJECTED");
        long unmatchedCount = materialRepository.countUnmatched();

        long materialsReviewed = confirmedCount + rejectedCount;

        // Calculate actual confirmed duplicates eliminated across ACTIVE groups
        long duplicatesEliminated = mappingRepository.countConfirmedDuplicatesEliminated();

        // Deduplication rate based strictly on reviewed volume
        double dedupRate = 0.0;
        if (materialsReviewed > 0) {
            dedupRate = ((double) duplicatesEliminated / materialsReviewed) * 100.0;
            dedupRate = Math.round(dedupRate * 10.0) / 10.0;
        }

        // Price-based savings use observed material values; only administrative
        // overhead remains assumption-driven because transaction-volume data is absent.
        BigDecimal directArbitrage = Optional.ofNullable(mappingRepository.sumActiveGroupPriceArbitrage())
                .orElse(BigDecimal.ZERO);
        List<BigDecimal> redundantPrices = Optional.ofNullable(mappingRepository.findRedundantItemNominalPrices())
                .orElse(List.of()).stream().filter(Objects::nonNull).sorted().toList();
        BigDecimal carryingRatePct = getAssumptionValue("inventory_carrying_cost_rate_pct", BigDecimal.valueOf(20));
        BigDecimal medianRedundantPrice = median(redundantPrices);
        BigDecimal inventoryHoldingAvoidance = medianRedundantPrice
                .multiply(BigDecimal.valueOf(redundantPrices.size()))
                .multiply(carryingRatePct.movePointLeft(2));
        BigDecimal cleanupAvoided = getAssumptionValue("master_data_cleanup_avoided_inr", BigDecimal.valueOf(25000));
        BigDecimal adminSavings = getAssumptionValue("admin_overhead_reduction_inr", BigDecimal.valueOf(15000));
        BigDecimal adminDataCleanupAvoidance = cleanupAvoided.add(adminSavings)
                .multiply(BigDecimal.valueOf(duplicatesEliminated));
        BigDecimal totalSavingsInr = directArbitrage.add(inventoryHoldingAvoidance)
                .add(adminDataCleanupAvoidance);
        double directArbitrageLakhs = toLakhs(directArbitrage);
        double inventoryHoldingLakhs = toLakhs(inventoryHoldingAvoidance);
        double adminCleanupLakhs = toLakhs(adminDataCleanupAvoidance);
        double savingsLakhs = toLakhs(totalSavingsInr);
        DashboardStatsDto.SavingsBreakdownDto savingsBreakdown =
                new DashboardStatsDto.SavingsBreakdownDto(
                        directArbitrageLakhs,
                        inventoryHoldingLakhs,
                        adminCleanupLakhs,
                        savingsLakhs,
                        carryingRatePct.doubleValue(),
                        redundantPrices.size());

        // CPSE Breakdown
        List<DashboardStatsDto.CpseStatDto> cpseStats = materialRepository.countMaterialsAndMappingsByCpse().stream()
                .map(row -> new DashboardStatsDto.CpseStatDto(
                        row.getCpseName(), row.getMaterialCount().intValue(), row.getMappedCount().intValue()))
                .toList();

        // Category distribution
        Map<String, Long> categoryDistribution = new LinkedHashMap<>();
        materialRepository.countByCategory().forEach(row ->
                categoryDistribution.put(row.getCategoryName().toUpperCase(), row.getMaterialCount()));

        return new DashboardStatsDto(
                totalMaterials,
                totalGroups,
                pendingCount,
                confirmedCount,
                rejectedCount,
                unmatchedCount,
                cpseRepository.count(),
                dedupRate,
                savingsLakhs,
                savingsBreakdown,
                cpseStats,
                categoryDistribution
        );
    }

    /**
     * W6.3: Rate Contract Candidates (Demand Aggregation for GeM / Centralized Procurement).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRateContractCandidates() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (MaterialMappingRepository.RateContractCandidateProjection row : mappingRepository.findRateContractCandidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("groupId", row.getGroupId());
            item.put("nationalMaterialCode", row.getNationalMaterialCode());
            item.put("standardizedDescription", row.getStandardizedDescription());
            item.put("category", row.getCategory());
            item.put("distinctCpseCount", row.getDistinctCpseCount());
            item.put("participatingCpses", Arrays.asList(row.getParticipatingCpses().split(", ")));
            item.put("totalMemberCount", row.getTotalMemberCount());
            item.put("rateContractRecommended", row.getDistinctCpseCount() >= 3
                    ? "HIGH_PRIORITY_JOINT_GEM_CONTRACT" : "RECOMMENDED_RATE_CONTRACT");
            candidates.add(item);
        }
        return candidates;
    }

    /**
     * W6.4: Price Variance Analytics across CPSEs on Unified Materials.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPriceVarianceReport() {
        List<Map<String, Object>> report = new ArrayList<>();
        Map<Long, Map<String, Map<String, BigDecimal>>> summariesByGroup = new HashMap<>();
        for (MaterialMappingRepository.CpsePriceProjection row : mappingRepository.findCpsePriceSummaries()) {
            summariesByGroup.computeIfAbsent(row.getGroupId(), ignored -> new LinkedHashMap<>()).put(
                    row.getCpseName(), Map.of(
                            "min", row.getMinPrice(),
                            "max", row.getMaxPrice(),
                            "average", row.getAveragePrice().setScale(2, RoundingMode.HALF_UP)));
        }
        for (MaterialMappingRepository.PriceVarianceProjection row : mappingRepository.findPriceVarianceGroups()) {
            BigDecimal min = row.getMinPriceInr();
            BigDecimal max = row.getMaxPriceInr();
            BigDecimal spread = max.subtract(min);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nationalCode", row.getNationalCode());
            item.put("description", row.getDescription());
            item.put("category", row.getCategory());
            item.put("minPriceInr", min);
            item.put("maxPriceInr", max);
            item.put("priceSpreadInr", spread);
            item.put("spreadPercentage", min.compareTo(BigDecimal.ZERO) > 0
                    ? spread.divide(min, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO);
            item.put("cpsePrices", summariesByGroup.getOrDefault(row.getGroupId(), Map.of()));
            report.add(item);
        }
        return report;
    }

    private BigDecimal getAssumptionValue(String key, BigDecimal defaultValue) {
        return assumptionRepository.findByKey(key)
                .map(ProcurementAssumption::getValue)
                .orElse(defaultValue);
    }

    private BigDecimal median(List<BigDecimal> sortedValues) {
        if (sortedValues.isEmpty()) return BigDecimal.ZERO;
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) return sortedValues.get(middle);
        return sortedValues.get(middle - 1).add(sortedValues.get(middle))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private double toLakhs(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(100000), 2, RoundingMode.HALF_UP).doubleValue();
    }
}
