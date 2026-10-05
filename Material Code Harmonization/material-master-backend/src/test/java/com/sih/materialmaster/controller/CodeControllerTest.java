package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.MaterialGroup;
import com.sih.materialmaster.repository.GroupRelationRepository;
import com.sih.materialmaster.repository.MaterialGroupRepository;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeControllerTest {

    @Mock MaterialGroupRepository groupRepository;
    @Mock MaterialMappingRepository mappingRepository;
    @Mock GroupRelationRepository relationRepository;

    @Test
    void searchReturnsPageAndClampsBounds() {
        MaterialGroup group = new MaterialGroup();
        group.setGroupId(1L);
        group.setProvisionalRef("PROV-2026-000001");
        group.setStatus("ACTIVE");
        group.setStandardizedDescription("General MRO item");
        group.setCreatedAt(LocalDateTime.now());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(groupRepository.findByStatus(eq("ACTIVE"), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(group)));
        when(mappingRepository.findCatalogMappingsForGroups(List.of(1L))).thenReturn(List.of());
        when(relationRepository.findForGroups(List.of(1L))).thenReturn(List.of());

        var response = new CodeController(groupRepository, mappingRepository, relationRepository)
                .searchCodes("", "ACTIVE", null, -4, 500, null);

        assertEquals(1, response.getBody().getTotalElements());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(100, pageableCaptor.getValue().getPageSize());
        assertTrue(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending());
    }
}
