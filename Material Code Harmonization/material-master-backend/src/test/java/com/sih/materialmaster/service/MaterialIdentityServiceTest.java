package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.MaterialCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MaterialIdentityServiceTest {

    private final MaterialIdentityService service = new MaterialIdentityService(mock(EntityManager.class));

    @Test
    void signatureIsDeterministicAcrossInputOrderAndCase() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("material", "cs");
        first.put("size", "50 mm");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("size", "50 MM");
        second.put("material", "CS");

        var a = service.computeAttributeSignature("pipe", List.of("material", "size"), first);
        var b = service.computeAttributeSignature("PIPE", List.of("size", "material"), second);

        assertTrue(a.complete());
        assertEquals(a.signature(), b.signature());
    }

    @Test
    void incompleteIdentityCannotBecomeMergeKey() {
        var result = service.computeAttributeSignature(
                "PIPE", List.of("material", "size"), Map.of("material", "CS"));
        assertFalse(result.complete());
        assertNull(result.signature());
    }

    @Test
    void catalogReferenceUsesCommodityAndSerial() {
        MaterialCategory category = new MaterialCategory();
        category.setCodeSegment("40");
        category.setCodeFamily("14");
        category.setCodeClass("07");
        assertEquals("CAT-401407-000042", service.generateCatalogReference(category, 42));
    }
}
