package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.*;

import java.util.List;

public interface MatchingClient {
    /**
     * Compares two material descriptions and returns a similarity score
     * between 0.0 and 1.0.
     */
    double compare(String description1, String description2);

    /**
     * Full attribute-aware pairwise comparison returning relationship label,
     * confidence tier, class probabilities, and conflict checklist.
     */
    CompareResponse compareDetailed(MaterialInfoDto materialA, MaterialInfoDto materialB);

    /**
     * Category-blocked candidate retrieval for a single material against candidates.
     */
    FindMatchesResponse findMatches(MaterialInfoDto material, List<MaterialInfoDto> candidates, int topK);

    /** Batch candidate scoring used by ingestion jobs to avoid one HTTP call per row. */
    List<FindMatchesResponse> findMatchesBatch(List<FindMatchesBatchQuery> queries);

    /**
     * WP1: Batch attribute extraction. Sends materials to Flask /extract-attributes and
     * returns a list of AttributeExtractionResult containing attributes + completeness flags.
     * Cached schemas are stored in HarmonizationService; this call only does extraction.
     */
    List<AttributeExtractionResult> extractAttributes(List<MaterialInfoDto> materials);

    /**
     * WP1: Fetch identity/variant schema for a category from Flask /schema/{category}.
     * Callers cache the result; this method may be called per-category at startup.
     */
    CategorySchemaDto getCategorySchema(String category);
}
