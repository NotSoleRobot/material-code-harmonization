package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDto {
    private Long userId;
    private String name;
    private String email;
    private String role; // OPERATOR | SENIOR_REVIEWER | ADMIN
    private CpseInfo cpse;
    private List<Long> assignedCategoryIds;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CpseInfo {
        private Long id;
        private String name;
        private String sector;
    }
}
