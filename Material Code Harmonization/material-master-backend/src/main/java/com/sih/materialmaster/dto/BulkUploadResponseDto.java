package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadResponseDto {
    private int totalProcessed;
    private int importedCount;
    private int skippedCount;
    private List<String> messages;
    private Long jobId;
}
