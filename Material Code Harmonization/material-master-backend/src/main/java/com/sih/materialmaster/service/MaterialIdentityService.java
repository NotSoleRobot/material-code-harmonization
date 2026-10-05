package com.sih.materialmaster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.entity.MaterialCategory;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds stable internal identities for candidate material groups. */
@Service
public class MaterialIdentityService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EntityManager entityManager;

    public MaterialIdentityService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Atomically allocates an internal reference number within one commodity class. */
    @Transactional
    public long allocateReferenceSerial(MaterialCategory category) {
        String commodity = commodityCode(category);
        Long categoryId = category != null ? category.getCategoryId() : null;
        Object result = entityManager.createNativeQuery(
                        "INSERT INTO material_code_serial (commodity_code, next_serial) " +
                        "VALUES (:commodity, (SELECT COALESCE(MAX(code_serial), 0) + 2 FROM material_group " +
                        "WHERE (:categoryId IS NULL AND category_id IS NULL) OR category_id = :categoryId)) " +
                        "ON CONFLICT (commodity_code) DO UPDATE SET next_serial = " +
                        "GREATEST(material_code_serial.next_serial, " +
                        "(SELECT COALESCE(MAX(code_serial), 0) + 1 FROM material_group " +
                        "WHERE (:categoryId IS NULL AND category_id IS NULL) OR category_id = :categoryId)) + 1 " +
                        "RETURNING next_serial - 1")
                .setParameter("commodity", commodity)
                .setParameter("categoryId", categoryId)
                .getSingleResult();
        if (result instanceof Number number) return number.longValue();
        throw new IllegalStateException("Unable to allocate catalog reference for commodity " + commodity);
    }

    public String generateCatalogReference(MaterialCategory category, long serial) {
        return String.format("CAT-%s-%06d", commodityCode(category), serial);
    }

    private String commodityCode(MaterialCategory category) {
        String segment = category != null && category.getCodeSegment() != null ? category.getCodeSegment() : "40";
        String family = category != null && category.getCodeFamily() != null ? category.getCodeFamily() : "14";
        String clazz = category != null && category.getCodeClass() != null ? category.getCodeClass() : "07";
        return String.format("%2s%2s%2s", segment, family, clazz).replace(' ', '0');
    }

    public SignatureResult computeAttributeSignature(String categoryName,
                                                     List<String> identityCriticalKeys,
                                                     Map<String, Object> attributes) {
        try {
            boolean complete = identityCriticalKeys != null && !identityCriticalKeys.isEmpty() &&
                    identityCriticalKeys.stream().allMatch(key -> {
                        Object value = attributes != null ? attributes.get(key) : null;
                        return value != null && !value.toString().isBlank();
                    });
            if (!complete) return new SignatureResult(null, false);

            Map<String, Object> canonical = new TreeMap<>();
            if (categoryName != null) canonical.put("__category", categoryName.trim().toUpperCase());
            for (String key : identityCriticalKeys) {
                canonical.put(key, attributes.get(key).toString().trim().toUpperCase());
            }

            String json = objectMapper.writeValueAsString(canonical);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) hex.append(String.format("%02X", value));
            return new SignatureResult(hex.toString(), true);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to compute attribute signature: " + ex.getMessage(), ex);
        }
    }

    public record SignatureResult(String signature, boolean complete) {}
}
