package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.DashboardStatsDto;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialGroupRepository;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import com.sih.materialmaster.repository.MaterialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock MaterialRepository materialRepository;
    @Mock MaterialGroupRepository groupRepository;
    @Mock MaterialMappingRepository mappingRepository;
    @Mock CpseRepository cpseRepository;
    @InjectMocks AnalyticsService analyticsService;

    @Test
    void dashboardUsesAggregateCountsAndReviewedVolumeForDeduplicationRate() {
        when(materialRepository.count()).thenReturn(12L);
        when(groupRepository.countByStatus("ACTIVE")).thenReturn(1L);
        when(mappingRepository.countByStatus("PENDING")).thenReturn(2L);
        when(mappingRepository.countByStatus("CONFIRMED")).thenReturn(6L);
        when(mappingRepository.countByStatus("REJECTED")).thenReturn(2L);
        when(materialRepository.countUnmatched()).thenReturn(2L);
        when(mappingRepository.countConfirmedDuplicatesEliminated()).thenReturn(3L);
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

        assertEquals(1L, result.getTotalUniqueGroups());
        assertEquals(37.5, result.getDeduplicationRate());
        assertEquals(7L, result.getCategoryDistribution().get("PIPE"));
        assertEquals(5L, result.getCpseBreakdown().get(0).getMappedCount());
        verify(mappingRepository).countConfirmedDuplicatesEliminated();
        verify(materialRepository).countMaterialsAndMappingsByCpse();
        verify(materialRepository).countByCategory();
    }

}
