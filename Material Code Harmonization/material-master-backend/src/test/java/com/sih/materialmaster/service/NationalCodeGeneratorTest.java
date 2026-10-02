package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.MaterialCategory;
import com.sih.materialmaster.util.Iso7064Mod3736;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NationalCodeGeneratorTest {

    private NationalCodeGenerator generator;
    private MaterialCategory commodityCategory;

    @BeforeEach
    void setUp() {
        EntityManager entityManager = mock(EntityManager.class);
        Query sequenceQuery = mock(Query.class);
        when(entityManager.createNativeQuery("SELECT nextval('numm_serial_seq')"))
                .thenReturn(sequenceQuery);
        when(sequenceQuery.getSingleResult()).thenReturn(101L);
        Query classSerialQuery = mock(Query.class);
        when(entityManager.createNativeQuery(argThat(sql -> sql != null && sql.startsWith("INSERT INTO material_code_serial"))))
                .thenReturn(classSerialQuery);
        when(classSerialQuery.setParameter(anyString(), any())).thenReturn(classSerialQuery);
        when(classSerialQuery.getSingleResult()).thenReturn(101L);
        generator = new NationalCodeGenerator(entityManager);

        commodityCategory = new MaterialCategory();
        commodityCategory.setLevel(3);
        commodityCategory.setCodeSegment("40");
        commodityCategory.setCodeFamily("14");
        commodityCategory.setCodeClass("18");
        commodityCategory.setName("Tubing");
    }

    @Test
    @DisplayName("Generate canonical NUMM code with UNSPSC 3-level prefix and ISO 7064 MOD 37,36 checksum")
    void testMintNationalCode() {
        String code = generator.mintNationalCode(commodityCategory);

        assertNotNull(code);
        // Semi-significant format: NUMM-401418-XX-000-000-000101-K
        assertTrue(code.startsWith("NUMM-401418-XX-000-STD-000101-"),
                "Missing attributes must use neutral placeholders, never fabricated engineering values");

        // Validate ISO 7064 checksum
        assertTrue(Iso7064Mod3736.validate(code), "Minted national code must pass ISO 7064 checksum validation");
    }

    @Test
    @DisplayName("Generate provisional code for candidate group")
    void testGenerateProvisionalRef() {
        String provCode = generator.generateProvisionalRef(456L);

        assertNotNull(provCode);
        assertTrue(provCode.startsWith("PROV-"), "Provisional code must start with PROV-");
        assertTrue(provCode.endsWith("-000456"), "Provisional code must end with padded ID");
    }

    @Test
    @DisplayName("Engineering tokens are encoded without ambiguous substring matches")
    void materialAndRatingKeysDoNotCollide() {
        String code = generator.mintNationalCode(
                commodityCategory,
                Map.of("material", "BRASS", "nominal_size_mm", "25", "pressure_class", "CLASS 800"),
                "PROV-401418-000101");

        assertTrue(code.startsWith("NUMM-401418-BR-025-800-000101-"));
        assertTrue(Iso7064Mod3736.validate(code));
    }

    @Test
    @DisplayName("Deterministic attribute signature SHA-256 generation")
    void testComputeAttributeSignature() {
        Map<String, Object> attrs1 = new LinkedHashMap<>();
        attrs1.put("size", "50mm");
        attrs1.put("schedule", "SCH40");
        attrs1.put("material", "Carbon Steel");

        // Same keys/values in different order
        Map<String, Object> attrs2 = new LinkedHashMap<>();
        attrs2.put("material", "Carbon Steel");
        attrs2.put("size", "50MM");
        attrs2.put("schedule", "sch40");

        List<String> identityKeys = Arrays.asList("size", "schedule", "material");

        NationalCodeGenerator.SignatureResult sig1 = generator.computeAttributeSignature("PIPE", identityKeys, attrs1);
        NationalCodeGenerator.SignatureResult sig2 = generator.computeAttributeSignature("pipe", identityKeys, attrs2);

        assertNotNull(sig1);
        assertTrue(sig1.complete());
        assertEquals(64, sig1.signature().length(), "SHA-256 signature must be 64 hex characters");
        assertEquals(sig1.signature(), sig2.signature(), "Signatures must be deterministic regardless of insertion order or case");
    }

    @Test
    @DisplayName("Incomplete identity attributes never produce a merge signature")
    void incompleteIdentityHasNoSignature() {
        NationalCodeGenerator.SignatureResult result = generator.computeAttributeSignature(
                "PIPE", List.of("material", "nominal_size_mm"), Map.of("material", "CS"));

        assertFalse(result.complete());
        assertNull(result.signature());
    }
}
