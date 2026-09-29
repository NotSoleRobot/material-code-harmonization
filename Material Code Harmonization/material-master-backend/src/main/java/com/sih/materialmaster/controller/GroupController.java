package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.PublishableGroupDto;
import com.sih.materialmaster.entity.Material;
import com.sih.materialmaster.entity.MaterialGroup;
import com.sih.materialmaster.entity.MaterialMapping;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.GovernanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GovernanceService governance;
    private final UserRepository users;
    private final MaterialMappingRepository mappingRepository;

    public GroupController(GovernanceService governance,
                           UserRepository users,
                           MaterialMappingRepository mappingRepository) {
        this.governance = governance;
        this.users = users;
        this.mappingRepository = mappingRepository;
    }

    /**
     * WP4 Task 4 / D3: Retrieve groups in PROPOSED state with >= 1 CONFIRMED mapping awaiting national code publication.
     */
    @GetMapping("/publishable")
    public ResponseEntity<List<PublishableGroupDto>> getPublishableGroups() {
        List<MaterialGroup> groups = governance.getPublishableGroups();
        List<PublishableGroupDto> dtos = new ArrayList<>();
        Map<Long, List<MaterialMapping>> confirmedByGroup = groups.isEmpty() ? Map.of() :
                mappingRepository.findConfirmedForGroups(groups.stream().map(MaterialGroup::getGroupId).toList())
                        .stream().collect(java.util.stream.Collectors.groupingBy(m -> m.getGroup().getGroupId()));

        for (MaterialGroup g : groups) {
            List<MaterialMapping> confirmed = confirmedByGroup.getOrDefault(g.getGroupId(), List.of());

            Set<String> cpseSet = new HashSet<>();
            List<PublishableGroupDto.MemberMaterialDto> memberDtos = new ArrayList<>();

            for (MaterialMapping m : confirmed) {
                Material mat = m.getMaterial();
                if (mat != null) {
                    String cpseName = mat.getCpse() != null ? mat.getCpse().getName() : "Unknown";
                    cpseSet.add(cpseName);
                    String reviewerName = m.getReviewedBy() != null ? m.getReviewedBy().getName() : "System";
                    String reviewTime = m.getReviewedAt() != null ? m.getReviewedAt().toString() : "";

                    memberDtos.add(new PublishableGroupDto.MemberMaterialDto(
                            mat.getMaterialId(),
                            cpseName,
                            mat.getCpseMaterialCode(),
                            mat.getDescription(),
                            reviewerName,
                            reviewTime
                    ));
                }
            }

            PublishableGroupDto dto = new PublishableGroupDto(
                    g.getGroupId(),
                    g.getProvisionalRef(),
                    g.getCategory() != null ? g.getCategory().getName() : "GENERAL",
                    g.getStandardizedDescription(),
                    g.getStandardizedSpecification(),
                    g.getStandardizedUom(),
                    confirmed.size(),
                    cpseSet.size(),
                    memberDtos
            );
            dtos.add(dto);
        }

        return ResponseEntity.ok(dtos);
    }

    /**
     * WP4 Task 3 / D3: Publish group and mint official National Material Code.
     */
    @PostMapping("/{id}/mint")
    public Map<String, String> mint(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        String code = governance.mintGroup(id, users.findById(principal.getUserId()).orElseThrow());
        return Map.of("code", code, "status", "ACTIVE", "message", "National code minted successfully: " + code);
    }
}
