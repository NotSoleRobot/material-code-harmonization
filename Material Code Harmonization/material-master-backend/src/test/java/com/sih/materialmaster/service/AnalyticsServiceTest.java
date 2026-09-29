package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.DashboardStatsDto;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialGroupRepository;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import com.sih.materialmaster.repository.MaterialRepository;
import com.sih.materialmaster.repository.ProcurementAssumptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock MaterialRepository materialRepository;
    @Mock MaterialGroupRepository groupRepository;
    @Mock MaterialMappingRepository mappingRepository;
    @Mock CpseRepository cpseRepository;
    @Mock ProcurementAssumptionRepository assumptionRepository;
    @InjectMocks AnalyticsService analyticsService;

    @Test
    void dashboardUsesAggregateCountsAndReviewedVolumeForDeduplicationRate() {
        when(materialRepository.count()).thenReturn(12L);
        when(groupRepository.count()).thenReturn(4L);
        when(mappingRepository.countByStatus("PENDING")).thenReturn(2L);
        when(mappingRepository.countByStatus("CONFIRMED")).thenReturn(6L);
        when(mappingRepository.countByStatus("REJECTED")).thenReturn(2L);
        when(materialRepository.countUnmatched()).thenReturn(2L);
        when(mappingRepository.countConfirmedDuplicatesEliminated()).thenReturn(3L);
        when(mappingRepository.sumActiveGroupPriceArbitrage()).thenReturn(BigDecimal.ZERO);
        when(mappingRepository.findRedundantItemNominalPrices()).thenReturn(List.of());
        when(cpseRepository.count()).thenReturn(2L);

        MaterialRepository.CpseMappingCount cpse = mock(MaterialRepository.CpseMappingCount.class);
        when(cpse.getCpseName()).thenReturn("ONGC");
        when(cpse.getMaterialCount()).thenReturn(7L);
        when(cpse.getMappedCount()).thenReturn(5L);
        when(materialRepository.countMaterialsAndMappingsByCpse()).thenReturn(List.of(cpse));

        MaterialRepository.CategoryCount category = mock(MaterialRepository.CategoryCount.class);
        when(category.getCategoryName()).thenReturn("Pipe");
        when(category.getMaterialCount()).thenReturn(7L);
        when(materialRepository.countByCategory()).thenReturn(List.of(category));

        DashboardStatsDto result = analyticsService.getDashboardStats();

        assertEquals(37.5, result.getDeduplicationRate());
        assertEquals(1.2, result.getEstimatedSavingsInrLakhs());
        assertEquals(1.2, result.getSavingsBreakdown().getAdminDataCleanupAvoidanceLakhs());
        assertEquals(7L, result.getCategoryDistribution().get("PIPE"));
        assertEquals(5L, result.getCpseBreakdown().get(0).getMappedCount());
        verify(mappingRepository).countConfirmedDuplicatesEliminated();
        verify(materialRepository).countMaterialsAndMappingsByCpse();
        verify(materialRepository).countByCategory();
    }

    @Test
    void dashboardSeparatesObservedArbitrageAndInventoryHoldingSavings() {
        when(mappingRepository.sumActiveGroupPriceArbitrage()).thenReturn(new BigDecimal("50000"));
        when(mappingRepository.findRedundantItemNominalPrices()).thenReturn(List.of(
                new BigDecimal("100000"), new BigDecimal("200000"), new BigDecimal("300000")));
        when(assumptionRepository.findByKey("inventory_carrying_cost_rate_pct"))
                .thenReturn(java.util.Optional.of(assumption("20")));

        DashboardStatsDto result = analyticsService.getDashboardStats();

        assertEquals(0.5, result.getSavingsBreakdown().getDirectPriceArbitrageLakhs());
        assertEquals(1.2, result.getSavingsBreakdown().getInventoryHoldingAvoidanceLakhs());
        assertEquals(1.7, result.getEstimatedSavingsInrLakhs());
    }

    private com.sih.materialmaster.entity.ProcurementAssumption assumption(String value) {
        var assumption = new com.sih.materialmaster.entity.ProcurementAssumption();
        assumption.setValue(new BigDecimal(value));
        return assumption;
    }

    @Test
    void priceVarianceDoesNotDivideByZero() {
        MaterialMappingRepository.PriceVarianceProjection variance =
                mock(MaterialMappingRepository.PriceVarianceProjection.class);
        when(variance.getGroupId()).thenReturn(42L);
        when(variance.getNationalCode()).thenReturn("NUMM-40-14-16-000042-A");
        when(variance.getDescription()).thenReturn("Test material");
        when(variance.getCategory()).thenReturn("PIPE");
        when(variance.getMinPriceInr()).thenReturn(BigDecimal.ZERO);
        when(variance.getMaxPriceInr()).thenReturn(BigDecimal.TEN);
        when(mappingRepository.findPriceVarianceGroups()).thenReturn(List.of(variance));
        when(mappingRepository.findCpsePriceSummaries()).thenReturn(List.of());

        var result = analyticsService.getPriceVarianceReport();

        assertEquals(BigDecimal.ZERO, result.get(0).get("spreadPercentage"));
        assertTrue(((java.util.Map<?, ?>) result.get(0).get("cpsePrices")).isEmpty());
    }
}
