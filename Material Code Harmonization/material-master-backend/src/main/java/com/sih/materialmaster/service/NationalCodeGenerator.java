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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class NationalCodeGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EntityManager entityManager;

    public NationalCodeGenerator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Legacy global allocator retained only for old callers during migration. */
    @Transactional
    public long getNextSerial() {
        Query query = entityManager.createNativeQuery("SELECT nextval('numm_serial_seq')");
        Object result = query.getSingleResult();
        if (result instanceof Number num) {
            return num.longValue();
        }
        throw new IllegalStateException("Database sequence numm_serial_seq returned a non-numeric value");
    }

    /** Atomically allocates a serial within one six-digit commodity class. */
    @Transactional
    public long allocateSerial(MaterialCategory category) {
        String commodity = commodityCode(category);
        Object result = entityManager.createNativeQuery(
                        "INSERT INTO material_code_serial (commodity_code, next_serial) VALUES (:commodity, 2) " +
                        "ON CONFLICT (commodity_code) DO UPDATE SET next_serial = material_code_serial.next_serial + 1 " +
                        "RETURNING next_serial - 1")
                .setParameter("commodity", commodity)
                .getSingleResult();
        if (result instanceof Number number) return number.longValue();
        throw new IllegalStateException("Unable to allocate code serial for commodity " + commodity);
    }

    /**
     * Mints a canonical National Material Code using ISO 7064 MOD 37,36 check character.
     * Backwards-compatible overload.
     */
    @Transactional
    public String mintNationalCode(MaterialCategory category) {
        return mintNationalCode(category, Collections.emptyMap(), null);
    }

    /**
     * Semi-Significant Intelligent National Material Code (WP2.1 / ISO 7064 MOD 37,36).
     * Structure: NUMM-CCCCCC-MM-DDD-RRR-NNNNNN-K
     *
     * Example: NUMM-401407-CS-050-S40-000042-K
     */
    @Transactional
    public String mintNationalCode(MaterialCategory category, Map<String, Object> attributes, String provisionalRef) {
        String commodity = commodityCode(category);

        String matKey = extractMaterialKey(attributes);
        String dimKey = extractDimensionKey(attributes);
        String ratingKey = extractRatingKey(attributes);

        String serialStr = extractSerialFromProvisional(provisionalRef);
        if (serialStr == null) {
            long serial = allocateSerial(category);
            serialStr = String.format("%06d", serial);
        }

        String baseCode = String.format("NUMM-%s-%s-%s-%s-%s", commodity, matKey, dimKey, ratingKey, serialStr);
        char checkChar = com.sih.materialmaster.util.Iso7064Mod3736.computeCheckChar(
                baseCode.replace("-", ""));
        return baseCode + "-" + checkChar;
    }

    /**
     * Generates a provisional reference for candidate groups awaiting human review.
     * Synchronized with UNSPSC commodity code and serial sequence (e.g. PROV-401407-000042).
     */
    public String generateProvisionalRef(MaterialCategory category, long id) {
        String commodity = commodityCode(category);
        return String.format("PROV-%s-%06d", commodity, id);
    }

    public String commodityCode(MaterialCategory category) {
        String segment = category != null && category.getCodeSegment() != null ? category.getCodeSegment() : "40";
        String family = category != null && category.getCodeFamily() != null ? category.getCodeFamily() : "14";
        String clazz = category != null && category.getCodeClass() != null ? category.getCodeClass() : "07";
        return String.format("%2s%2s%2s", segment, family, clazz).replace(' ', '0');
    }

    public String generateProvisionalRef(long id) {
        return generateProvisionalRef(null, id);
    }

    public String extractSerialFromProvisional(String provisionalRef) {
        if (provisionalRef != null && provisionalRef.contains("-")) {
            String[] parts = provisionalRef.split("-");
            String last = parts[parts.length - 1];
            if (last.matches("\\d+")) {
                try {
                    return String.format("%06d", Long.parseLong(last));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private String extractMaterialKey(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "XX";
        String m = getAttrString(attrs, "material_type", "material", "grade", "extracted_material_type");
        if (m == null) return "XX";
        String upper = m.toUpperCase(Locale.ROOT).trim();
        String clean = upper.replaceAll("[^A-Z0-9]", "");
        if (clean.equals("SS") || clean.startsWith("SS304") || clean.startsWith("SS316")
                || clean.contains("STAINLESSSTEEL")) return "SS";
        if (clean.equals("CS") || clean.contains("CARBONSTEEL")
                || clean.startsWith("A106") || clean.startsWith("A53")) return "CS";
        if (clean.equals("MS") || clean.contains("MILDSTEEL")) return "MS";
        if (clean.equals("AS") || clean.contains("ALLOYSTEEL")) return "AS";
        if (clean.equals("CI") || clean.contains("CASTIRON")) return "CI";
        if (clean.equals("GI") || clean.contains("GALVANIZEDIRON") || clean.contains("GALVANISEDIRON")) return "GI";
        if (clean.equals("BR") || clean.contains("BRASS") || clean.contains("BRONZE")) return "BR";
        if (clean.equals("PV") || clean.contains("PVC") || clean.contains("CPVC")
                || clean.contains("UPVC") || clean.contains("PLASTIC") || clean.contains("HDPE")) return "PV";
        if (clean.equals("AL") || clean.contains("ALUMINIUM") || clean.contains("ALUMINUM")) return "AL";
        if (clean.equals("CU") || clean.contains("COPPER")) return "CU";
        return clean.length() >= 2 ? clean.substring(0, 2) : (clean.length() == 1 ? clean + "X" : "XX");
    }

    private String extractDimensionKey(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "000";
        String d = getAttrString(attrs, "nominal_size_mm", "dimension", "size", "diameter", "bore_diameter_mm", "extracted_dimension");
        if (d == null) return "000";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(d);
        if (matcher.find()) {
            try {
                double parsed = Double.parseDouble(matcher.group(1));
                if (d.contains("\"") || d.toUpperCase().contains("INCH")) {
                    parsed *= 25.4;
                }
                int val = (int) Math.round(parsed);
                return String.format("%03d", Math.min(999, val));
            } catch (Exception ignored) {}
        }
        return "000";
    }

    private String extractRatingKey(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "STD";
        String r = getAttrString(attrs, "schedule", "pressure_class", "rating", "voltage_grade");
        if (r == null) return "STD";
        String clean = r.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9#]", "");
        java.util.regex.Matcher schedule = java.util.regex.Pattern
                .compile("(?:SCH|SCHEDULE|^S)(40|80|160)$").matcher(clean);
        if (schedule.find()) {
            return "160".equals(schedule.group(1)) ? "160" : "S" + schedule.group(1);
        }
        if (clean.equals("40")) return "S40";
        if (clean.equals("80")) return "S80";
        if (clean.equals("160")) return "160";
        java.util.regex.Matcher pressureClass = java.util.regex.Pattern
                .compile("(?:CLASS|CL|LB|#)?(150|300|600|800|900)$").matcher(clean);
        if (pressureClass.find()) return pressureClass.group(1);
        if (clean.equals("XS") || clean.equals("SCHXS")) return "SXS";
        clean = clean.replace("#", "");
        return clean.length() >= 3 ? clean.substring(0, 3) : (clean + "STD").substring(0, 3);
    }

    private String getAttrString(Map<String, Object> attrs, String... keys) {
        for (String k : keys) {
            Object v = attrs.get(k);
            if (v != null && !v.toString().isBlank()) {
                return v.toString().trim();
            }
        }
        return null;
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
