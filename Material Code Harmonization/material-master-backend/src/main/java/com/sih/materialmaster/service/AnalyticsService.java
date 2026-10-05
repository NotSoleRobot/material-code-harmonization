package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.DashboardStatsDto;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialGroupRepository;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import com.sih.materialmaster.repository.MaterialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {
    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository groupRepository;
    private final MaterialMappingRepository mappingRepository;
    private final CpseRepository cpseRepository;

    public AnalyticsService(MaterialRepository materialRepository,
                            MaterialGroupRepository groupRepository,
                            MaterialMappingRepository mappingRepository,
                            CpseRepository cpseRepository) {
        this.materialRepository = materialRepository;
        this.groupRepository = groupRepository;
        this.mappingRepository = mappingRepository;
        this.cpseRepository = cpseRepository;
    }

    @Transactional(readOnly = true)
    public DashboardStatsDto getDashboardStats() {
        long totalMaterials = materialRepository.count();
        long totalGroups = groupRepository.countByStatus("ACTIVE");
        long pendingCount = mappingRepository.countByStatus("PENDING");
        long confirmedCount = mappingRepository.countByStatus("CONFIRMED");
        long rejectedCount = mappingRepository.countByStatus("REJECTED");
        long unmatchedCount = materialRepository.countUnmatched();
        long reviewed = confirmedCount + rejectedCount;
        long duplicatesConsolidated = mappingRepository.countConfirmedDuplicatesEliminated();
        double consolidationRate = reviewed == 0 ? 0.0
                : Math.round(((double) duplicatesConsolidated / reviewed) * 1000.0) / 10.0;

        List<DashboardStatsDto.CpseStatDto> cpseStats = materialRepository
                .countMaterialsAndMappingsByCpse().stream()
                .map(row -> new DashboardStatsDto.CpseStatDto(
                        row.getCpseName(), row.getMaterialCount().intValue(), row.getMappedCount().intValue()))
                .toList();
        Map<String, Long> categoryDistribution = new LinkedHashMap<>();
        materialRepository.countByCategory().forEach(row ->
                categoryDistribution.put(row.getCategoryName().toUpperCase(), row.getMaterialCount()));

        return new DashboardStatsDto(totalMaterials, totalGroups, pendingCount, confirmedCount,
                rejectedCount, unmatchedCount, cpseRepository.count(), consolidationRate,
                cpseStats, categoryDistribution);
    }
}
