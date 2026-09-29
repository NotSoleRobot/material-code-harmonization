package com.sih.materialmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.exception.MatchingServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production implementation of MatchingClient that talks to the Python
 * Flask matching microservice (RandomForest + TF-IDF + RapidFuzz engine).
 */
@Service
@Profile("python-matching")
public class PythonMatchingClient implements MatchingClient {

    private static final Logger log = LoggerFactory.getLogger(PythonMatchingClient.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final Map<String, CachedSchema> schemaCache = new ConcurrentHashMap<>();
    private static final long SCHEMA_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    public PythonMatchingClient(
            @Value("${matching.service.url:http://localhost:5000}") String baseUrl,
            @Value("${matching.service.token:}") String serviceToken,
            @Value("${matching.service.connect-timeout-ms:5000}") int connectTimeout,
            @Value("${matching.service.read-timeout-ms:60000}") int readTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeout));

        RestClient.Builder clientBuilder = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory);
        if (serviceToken != null && !serviceToken.isBlank()) {
            clientBuilder.defaultHeader("X-Service-Token", serviceToken);
        }
        this.restClient = clientBuilder.build();
        log.info("Initialized PythonMatchingClient pointing to: {} (timeouts: connect={}ms, read={}ms)", this.baseUrl, connectTimeout, readTimeout);
    }

    @Override
    public double compare(String description1, String description2) {
        MaterialInfoDto a = new MaterialInfoDto(null, description1, "UNKNOWN", "", null, null, null);
        MaterialInfoDto b = new MaterialInfoDto(null, description2, "UNKNOWN", "", null, null, null);
        CompareResponse resp = compareDetailed(a, b);
        return resp.getConfidence();
    }

    @Override
    public CompareResponse compareDetailed(MaterialInfoDto materialA, MaterialInfoDto materialB) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("material_a", toPayloadMap(materialA));
            body.put("material_b", toPayloadMap(materialB));

            return restClient.post()
                    .uri("/compare")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(CompareResponse.class);
        } catch (Exception ex) {
            throw new MatchingServiceException("Python matching service /compare failed", ex);
        }
    }

    @Override
    public FindMatchesResponse findMatches(MaterialInfoDto material, List<MaterialInfoDto> candidates, int topK) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("material", toPayloadMap(material));

            List<Map<String, Object>> candidatePayloads = new ArrayList<>();
            for (MaterialInfoDto c : candidates) {
                candidatePayloads.add(toPayloadMap(c));
            }
            body.put("candidates", candidatePayloads);
            body.put("top_k", topK);

            return restClient.post()
                    .uri("/find-matches")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(FindMatchesResponse.class);
        } catch (Exception ex) {
            throw new MatchingServiceException("Python matching service /find-matches failed", ex);
        }
    }

    @Override
    public List<FindMatchesResponse> findMatchesBatch(List<FindMatchesBatchQuery> queries) {
        try {
            List<Map<String, Object>> payloadQueries = new ArrayList<>();
            for (FindMatchesBatchQuery query : queries) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("material", toPayloadMap(query.material()));
                entry.put("candidates", query.candidates().stream().map(this::toPayloadMap).toList());
                entry.put("top_k", query.topK());
                payloadQueries.add(entry);
            }
            Map<String, Object> body = Map.of("queries", payloadQueries);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/find-matches-batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            List<FindMatchesResponse> parsed = new ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            if (response != null && response.get("results") instanceof List<?> results) {
                for (Object result : results) {
                    parsed.add(mapper.convertValue(result, FindMatchesResponse.class));
                }
            }
            if (parsed.size() != queries.size()) {
                throw new MatchingServiceException("Matching service returned " + parsed.size()
                        + " batch results for " + queries.size() + " queries");
            }
            return parsed;
        } catch (Exception ex) {
            throw new MatchingServiceException("Python matching service /find-matches-batch failed", ex);
        }
    }

    @Override
    public List<AttributeExtractionResult> extractAttributes(List<MaterialInfoDto> materials) {
        try {
            List<Map<String, Object>> payloads = new ArrayList<>();
            for (MaterialInfoDto m : materials) {
                Map<String, Object> p = new HashMap<>();
                if (m.getMaterialId() != null) p.put("material_id", m.getMaterialId());
                p.put("description", m.getDescription() != null ? m.getDescription() : "");
                p.put("specification", m.getSpecification() != null ? m.getSpecification() : "");
                p.put("category", m.getCategory() != null ? m.getCategory() : "UNKNOWN");
                payloads.add(p);
            }
            Map<String, Object> body = new HashMap<>();
            body.put("materials", payloads);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/extract-attributes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            List<AttributeExtractionResult> results = new ArrayList<>();
            if (response != null && response.get("results") instanceof List<?> rawList) {
                for (Object raw : rawList) {
                    if (!(raw instanceof Map<?, ?> map)) continue;
                    AttributeExtractionResult r = new AttributeExtractionResult();
                    Object idObj = map.get("material_id");
                    if (idObj instanceof Number n) r.setMaterialId(n.longValue());
                    Object catObj = map.get("category");
                    r.setCategory(catObj != null ? catObj.toString() : "UNKNOWN");
                    Object attrsObj = map.get("attributes");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attrs = (attrsObj instanceof Map<?, ?> m) ? (Map<String, Object>) m : new HashMap<>();
                    r.setAttributes(attrs);
                    r.setIdentityCriticalPresent(Boolean.TRUE.equals(map.get("identity_critical_present")));
                    Object missObj = map.get("missing_identity_keys");
                    @SuppressWarnings("unchecked")
                    List<String> missing = (missObj instanceof List<?> l) ? (List<String>) l : List.of();
                    r.setMissingIdentityKeys(missing);
                    results.add(r);
                }
            }
            return results;
        } catch (Exception ex) {
            throw new MatchingServiceException("Python matching service /extract-attributes failed", ex);
        }
    }

    @Override
    public CategorySchemaDto getCategorySchema(String category) {
        String key = category.toUpperCase(Locale.ROOT);
        CachedSchema cached = schemaCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.schema();
        }
        try {
            CategorySchemaDto schema = restClient.get()
                    .uri("/schema/" + key)
                    .retrieve()
                    .body(CategorySchemaDto.class);
            if (schema == null) {
                throw new MatchingServiceException("Matching service returned an empty schema for " + key);
            }
            schemaCache.put(key, new CachedSchema(schema, now + SCHEMA_TTL_MILLIS));
            return schema;
        } catch (Exception ex) {
            throw new MatchingServiceException("Python matching service schema lookup failed for " + key, ex);
        }
    }

    private record CachedSchema(CategorySchemaDto schema, long expiresAt) {}

    private Map<String, Object> toPayloadMap(MaterialInfoDto info) {
        Map<String, Object> map = new HashMap<>();
        if (info.getMaterialId() != null) {
            map.put("material_id", info.getMaterialId());
        }
        map.put("description", info.getDescription() != null ? info.getDescription() : "");
        map.put("category", info.getCategory() != null ? info.getCategory() : "UNKNOWN");
        if (info.getSpecification() != null) {
            map.put("specification", info.getSpecification());
        }
        if (info.getCpseMaterialCode() != null) {
            map.put("cpse_material_code", info.getCpseMaterialCode());
        }
        if (info.getCpseName() != null) {
            map.put("cpse_name", info.getCpseName());
        }
        if (info.getExtractedAttributes() != null) {
            map.put("extracted_attributes", info.getExtractedAttributes());
        }
        return map;
    }
}
