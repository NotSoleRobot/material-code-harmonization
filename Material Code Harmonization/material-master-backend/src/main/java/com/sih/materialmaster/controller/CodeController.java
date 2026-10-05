package com.sih.materialmaster.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.dto.NationalCodeDetailsDto;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.sih.materialmaster.security.UserPrincipal;

import java.util.*;

/**
 * Endpoints for harmonized catalog lookup and search.
 */
@RestController
@RequestMapping("/api/catalog")
public class CodeController {

    private final MaterialGroupRepository groupRepository;
    private final MaterialMappingRepository mappingRepository;
    private final GroupRelationRepository groupRelationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeController(MaterialGroupRepository groupRepository,
                          MaterialMappingRepository mappingRepository,
                          GroupRelationRepository groupRelationRepository) {
        this.groupRepository = groupRepository;
        this.mappingRepository = mappingRepository;
        this.groupRelationRepository = groupRelationRepository;
    }

    /**
     * Resolves a catalog reference to its harmonized record and linked materials.
     */
    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public ResponseEntity<NationalCodeDetailsDto> resolveCode(@PathVariable String code,
            @AuthenticationPrincipal UserPrincipal viewer) {
        String clean = code.trim().toUpperCase();
        MaterialGroup group = groupRepository.findByCommonMaterialCode(clean)
                .or(() -> groupRepository.findByProvisionalRef(clean))
                .orElseThrow(() -> new IllegalArgumentException("No material group found for catalog reference: " + code));

        if (!"ACTIVE".equals(group.getStatus()) && !"SUPERSEDED".equals(group.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Code not found: " + code);
        }
        return ResponseEntity.ok(toDetailsDto(group, viewer));
    }

    /**
     * Search canonical national codes by text or partial code.
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<NationalCodeDetailsDto>> searchCodes(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "ALL") String status,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal viewer) {
        String query = (q != null && !q.isBlank()) ? q.trim() : "";
        String effectiveStatus = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? "ACTIVE" : status.trim().toUpperCase();
        String effectiveCategory = (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category))
                ? null : category.trim();
        var pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<MaterialGroup> groups;
        if (query.isBlank() && effectiveCategory == null) {
            groups = (effectiveStatus != null)
                    ? groupRepository.findByStatus(effectiveStatus, pageable)
                    : groupRepository.findAll(pageable);
        } else {
            groups = groupRepository.searchGroups(query, effectiveStatus, effectiveCategory, pageable);
        }

        if (groups.isEmpty()) return ResponseEntity.ok(groups.map(group -> toDetailsDto(group, viewer)));

        List<Long> groupIds = groups.getContent().stream().map(MaterialGroup::getGroupId).toList();
        Map<Long, List<MaterialMapping>> mappingsByGroup = mappingRepository
                .findCatalogMappingsForGroups(groupIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(mm -> mm.getGroup().getGroupId()));
        List<GroupRelation> pageRelations = groupRelationRepository.findForGroups(groupIds);
        Map<Long, List<GroupRelation>> relationsByGroup = new HashMap<>();
        for (GroupRelation relation : pageRelations) {
            relationsByGroup.computeIfAbsent(relation.getGroupA().getGroupId(), ignored -> new ArrayList<>()).add(relation);
            relationsByGroup.computeIfAbsent(relation.getGroupB().getGroupId(), ignored -> new ArrayList<>()).add(relation);
        }

        return ResponseEntity.ok(groups.map(group -> toDetailsDto(
                group,
                viewer,
                mappingsByGroup.getOrDefault(group.getGroupId(), List.of()),
                relationsByGroup.getOrDefault(group.getGroupId(), List.of()))));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> listCatalogCategories() {
        return ResponseEntity.ok(groupRepository.findCatalogCategoryNames());
    }

    private NationalCodeDetailsDto toDetailsDto(MaterialGroup group, UserPrincipal viewer) {
        return toDetailsDto(group, viewer,
                mappingRepository.findByGroup_GroupId(group.getGroupId()),
                groupRelationRepository.findByGroupA_GroupIdOrGroupB_GroupId(group.getGroupId(), group.getGroupId()));
    }

    private NationalCodeDetailsDto toDetailsDto(MaterialGroup group, UserPrincipal viewer,
                                                 List<MaterialMapping> mappings,
                                                 List<GroupRelation> relations) {
        NationalCodeDetailsDto dto = new NationalCodeDetailsDto();
        dto.setCommonMaterialCode(group.getCommonMaterialCode());
        dto.setProvisionalRef(group.getProvisionalRef());
        dto.setStandardizedDescription(group.getStandardizedDescription());
        dto.setStandardizedSpecification(group.getStandardizedSpecification());
        dto.setStandardizedUom(group.getStandardizedUom());
        dto.setAttributeSignature(group.getAttributeSignature());

        // Parse signature attributes
        if (group.getSignatureAttributes() != null) {
            try {
                Map<String, Object> attrs = objectMapper.readValue(group.getSignatureAttributes(), new TypeReference<Map<String, Object>>() {});
                dto.setSignatureAttributes(attrs);
            } catch (Exception ignored) {}
        }

        // Category hierarchy
        MaterialCategory cat = group.getCategory();
        if (cat != null) {
            dto.setCategoryName(cat.getName());
            dto.setCodeSegment(cat.getCodeSegment());
            dto.setCodeFamily(cat.getCodeFamily());
            dto.setCodeClass(cat.getCodeClass());

            StringBuilder path = new StringBuilder();
            if (cat.getParent() != null && cat.getParent().getParent() != null) {
                path.append(cat.getParent().getParent().getName()).append(" > ");
            }
            if (cat.getParent() != null) {
                path.append(cat.getParent().getName()).append(" > ");
            }
            path.append(cat.getName());
            dto.setCategoryPath(path.toString());
        }

        // Member materials
        Set<String> distinctCpses = new HashSet<>();
        List<NationalCodeDetailsDto.MemberMaterialDto> members = new ArrayList<>();

        for (MaterialMapping mm : mappings) {
            if (!"CONFIRMED".equals(mm.getStatus())) continue;
            Material m = mm.getMaterial();
            if (viewer != null && "OPERATOR".equals(viewer.getRole())
                    && (m.getCpse() == null || !Objects.equals(viewer.getCpseId(), m.getCpse().getCpseId()))) continue;
            String cpseName = (m.getCpse() != null) ? m.getCpse().getName() : "Unknown";
            distinctCpses.add(cpseName);

            NationalCodeDetailsDto.MemberMaterialDto mDto = new NationalCodeDetailsDto.MemberMaterialDto();
            mDto.setMaterialId(m.getMaterialId());
            mDto.setCpseName(cpseName);
            mDto.setCpseMaterialCode(m.getCpseMaterialCode());
            mDto.setRawDescription(m.getDescription());
            mDto.setRawSpecification(m.getSpecification());
            mDto.setUnitOfMeasure(m.getUnitOfMeasure());
            mDto.setNominalPrice(m.getNominalPrice());
            mDto.setMappingStatus(mm.getStatus());
            mDto.setConfidenceScore(mm.getConfidenceScore() != null ? mm.getConfidenceScore().doubleValue() : null);
            mDto.setConfidenceTier(mm.getConfidenceTier());
            members.add(mDto);
        }

        dto.setDistinctCpseCount(distinctCpses.size());
        dto.setMembers(members);
        dto.setStatus(members.stream().anyMatch(member -> "CONFIRMED".equals(member.getMappingStatus()))
                ? "HARMONIZED" : "PENDING_REVIEW");

        // Group relations (equivalents & variants)
        List<NationalCodeDetailsDto.RelatedGroupDto> relatedList = new ArrayList<>();
        for (GroupRelation gr : relations) {
            MaterialGroup other = gr.getGroupA().getGroupId().equals(group.getGroupId()) ? gr.getGroupB() : gr.getGroupA();
            if (!"ACTIVE".equals(other.getStatus())) continue;
            NationalCodeDetailsDto.RelatedGroupDto rDto = new NationalCodeDetailsDto.RelatedGroupDto();
            rDto.setGroupId(other.getGroupId());
            rDto.setCommonMaterialCode(other.getCommonMaterialCode());
            rDto.setProvisionalRef(other.getProvisionalRef());
            rDto.setRelationType(gr.getRelationType());
            rDto.setDescription(other.getStandardizedDescription());
            rDto.setConfidence(gr.getConfidence() != null ? gr.getConfidence().doubleValue() : null);
            relatedList.add(rDto);
        }
        dto.setRelatedGroups(relatedList);

        return dto;
    }
}
