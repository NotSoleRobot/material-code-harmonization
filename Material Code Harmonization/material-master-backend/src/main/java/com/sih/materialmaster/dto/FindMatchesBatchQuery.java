package com.sih.materialmaster.dto;

import java.util.List;

/** One query entry for the Flask /find-matches-batch endpoint. */
public record FindMatchesBatchQuery(
        MaterialInfoDto material,
        List<MaterialInfoDto> candidates,
        int topK
) {}
