package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.MaterialCategory;
import com.sih.materialmaster.repository.MaterialCategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialCategoryServiceTest {

    @Mock MaterialCategoryRepository repository;

    @Test
    void unknownCategoryFallsBackToGeneralMroWithoutDroppingTheRow() {
        MaterialCategory general = new MaterialCategory();
        general.setName(MaterialCategoryService.GENERAL_MRO);
        general.setCustom(true);
        when(repository.findByNameIgnoreCase("Safety Equipment")).thenReturn(Optional.empty());
        when(repository.findByNameIgnoreCase(MaterialCategoryService.GENERAL_MRO))
                .thenReturn(Optional.of(general));

        MaterialCategory resolved = new MaterialCategoryService(repository)
                .resolveOpenDomain("Safety Equipment");

        assertEquals(MaterialCategoryService.GENERAL_MRO, resolved.getName());
        assertTrue(resolved.getCustom());
    }

    @Test
    void displayAliasStillResolvesToSpecialistCategory() {
        MaterialCategory pipe = new MaterialCategory();
        pipe.setName("PIPE");
        when(repository.findByNameIgnoreCase("Pipes & Tubes")).thenReturn(Optional.empty());
        when(repository.findByNameIgnoreCase("PIPE")).thenReturn(Optional.of(pipe));

        MaterialCategory resolved = new MaterialCategoryService(repository)
                .resolveOpenDomain("Pipes & Tubes");

        assertEquals("PIPE", resolved.getName());
    }
}
