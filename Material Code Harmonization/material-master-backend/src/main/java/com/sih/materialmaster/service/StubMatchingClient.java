package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.AttributeExtractionResult;
import com.sih.materialmaster.dto.CategorySchemaDto;
import com.sih.materialmaster.dto.CompareResponse;
import com.sih.materialmaster.dto.FindMatchesBatchQuery;
import com.sih.materialmaster.dto.FindMatchesResponse;
import com.sih.materialmaster.dto.GenerateCodeResponse;
import com.sih.materialmaster.dto.MaterialInfoDto;
import com.sih.materialmaster.exception.MatchingServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Non-ML profile implementation required for a complete Spring bean graph.
 * It deliberately fails instead of fabricating scores, attributes, or codes.
 */
@Service
@Profile("!python-matching")
public class StubMatchingClient implements MatchingClient {

    private MatchingServiceException unavailable() {
        return new MatchingServiceException(
                "Matching is unavailable: activate the python-matching profile and run the NUMM matching service");
    }

    @Override
    public double compare(String description1, String description2) {
        throw unavailable();
    }

    @Override
    public CompareResponse compareDetailed(MaterialInfoDto materialA, MaterialInfoDto materialB) {
        throw unavailable();
    }

    @Override
    public FindMatchesResponse findMatches(MaterialInfoDto material, List<MaterialInfoDto> candidates, int topK) {
        throw unavailable();
    }

    @Override
    public List<FindMatchesResponse> findMatchesBatch(List<FindMatchesBatchQuery> queries) {
        throw unavailable();
    }

    @Override
    public GenerateCodeResponse generateCode(String category, Map<String, Object> attributes) {
        throw unavailable();
    }

    @Override
    public List<AttributeExtractionResult> extractAttributes(List<MaterialInfoDto> materials) {
        throw unavailable();
    }

    @Override
    public CategorySchemaDto getCategorySchema(String category) {
        throw unavailable();
    }
}
