package com.sih.materialmaster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.entity.MaterialCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class NationalCodeGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EntityManager entityManager;

    public NationalCodeGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Allocates a sequential serial number from the database sequence.
     */
    @Transactional
    public long getNextSerial() {
        Query query = entityManager.createNativeQuery("SELECT nextval('numm_serial_seq')");
        Object result = query.getSingleResult();
        if (result instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalStateException("Database sequence numm_serial_seq returned a non-numeric value");
    }

    /**
     * Mints a canonical National Material Code using ISO 7064 MOD 37,36 check character.
     * Structure: NUMM-SS-FF-CC-NNNNNN-K
     *
     * @param category Material category entity carrying segment, family, class codes
     * @return Formatted code with ISO 7064 check character (e.g. NUMM-40-14-07-000042-K)
     */
    @Transactional
    public String mintNationalCode(MaterialCategory category) {
        String segment = (category != null && category.getCodeSegment() != null) ? category.getCodeSegment() : "40";
        String family  = (category != null && category.getCodeFamily() != null) ? category.getCodeFamily() : "14";
        String clazz   = (category != null && category.getCodeClass() != null) ? category.getCodeClass() : "00";

        long serial = getNextSerial();
        String serialStr = String.format("%06d", serial);

        String baseCode = String.format("NUMM-%s-%s-%s-%s", segment, family, clazz, serialStr);
        char checkChar = com.sih.materialmaster.util.Iso7064Mod3736.computeCheckChar(
                baseCode.replace("-", ""));
        return baseCode + "-" + checkChar;
    }

    /**
     * Generates a provisional reference for candidate groups awaiting human review.
     * e.g. PROV-2026-000101
     */
    public String generateProvisionalRef(long id) {
        int currentYear = Year.now().getValue();
        return String.format("PROV-%d-%06d", currentYear, id);
    }

    /**
     * WP1: Computes the deterministic SHA-256 attribute signature from identity-critical attributes.
     *
     * @param categoryName Category name (used as namespace)
     * @param identityCriticalKeys Keys that are considered identity-critical for this category
     * @param attributes The full extracted attributes map
     * @return SignatureResult containing the hex signature and whether it is complete
     */
    public SignatureResult computeAttributeSignature(String categoryName,
                                                     List<String> identityCriticalKeys,
                                                     Map<String, Object> attributes) {
        try {
            // Check completeness first: all identity-critical keys must be present and non-blank
            boolean complete = identityCriticalKeys != null && !identityCriticalKeys.isEmpty() &&
                    identityCriticalKeys.stream().allMatch(k -> {
                        Object v = attributes != null ? attributes.get(k) : null;
                        return v != null && !v.toString().isBlank();
                    });

            // An incomplete identity must never become a merge key. Returning
            // null here is the completeness gate specified by D1.
            if (!complete) {
                return new SignatureResult(null, false);
            }

            // Build signature using only identity-critical + category namespace
            Map<String, Object> sortedAttrs = new TreeMap<>();
            if (categoryName != null) {
                sortedAttrs.put("__category", categoryName.trim().toUpperCase());
            }
            if (attributes != null) {
                for (String key : identityCriticalKeys) {
                    Object val = attributes.get(key);
                    if (val != null && !val.toString().isBlank()) {
                        sortedAttrs.put(key, val.toString().trim().toUpperCase());
                    }
                }
            }

            String canonicalJson = objectMapper.writeValueAsString(sortedAttrs);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String signature = hexString.toString().toUpperCase();
            return new SignatureResult(signature, complete);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to compute attribute signature: " + e.getMessage(), e);
        }
    }

    /**
     * Value object returned by computeAttributeSignature.
     */
    public record SignatureResult(String signature, boolean complete) {}
}
