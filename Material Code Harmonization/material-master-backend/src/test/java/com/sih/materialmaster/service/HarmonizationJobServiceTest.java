package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.Material;
import com.sih.materialmaster.entity.MaterialMapping;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarmonizationJobServiceTest {

    @Test
    void existingMaterialWithActiveMappingIsNotReprocessed() {
        MaterialMappingRepository mappings = mock(MaterialMappingRepository.class);
        HarmonizationJobService service = serviceWith(mappings);
        Material material = new Material();
        material.setMaterialId(42L);
        when(mappings.findActiveByMaterialId(42L)).thenReturn(Optional.of(new MaterialMapping()));

        assertTrue(service.shouldSkipExistingMaterial(material));
    }

    @Test
    void existingButUnmappedMaterialCanEnterHarmonization() {
        MaterialMappingRepository mappings = mock(MaterialMappingRepository.class);
        HarmonizationJobService service = serviceWith(mappings);
        Material material = new Material();
        material.setMaterialId(42L);
        when(mappings.findActiveByMaterialId(42L)).thenReturn(Optional.empty());

        assertFalse(service.shouldSkipExistingMaterial(material));
    }

    private HarmonizationJobService serviceWith(MaterialMappingRepository mappings) {
        return new HarmonizationJobService(
                mock(com.sih.materialmaster.repository.HarmonizationJobRepository.class),
                mock(HarmonizationService.class),
                mock(com.sih.materialmaster.repository.MaterialRepository.class),
                mappings,
                mock(com.sih.materialmaster.repository.CpseRepository.class),
                mock(com.sih.materialmaster.repository.UserRepository.class),
                mock(MaterialCategoryService.class),
                mock(AuditService.class),
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(jakarta.persistence.EntityManager.class),
                Runnable::run);
    }
}
