package com.sih.materialmaster.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.dto.CodeValidationDto;
import com.sih.materialmaster.dto.NationalCodeDetailsDto;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import com.sih.materialmaster.util.Iso7064Mod3736;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.sih.materialmaster.security.UserPrincipal;

import java.util.*;

/**
 * Endpoints for National Material Code lookup, validation, and search (W2.6 / FR6 / FR7).
 * External ERP systems and internal users can resolve codes and check validity.
 */
@RestController
@RequestMapping("/api/codes")
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
     * Resolves a national material code (or provisional ref) to canonical record and linked materials.
     */
    @GetMapping("/{code}")
    public ResponseEntity<NationalCodeDetailsDto> resolveCode(@PathVariable String code,
            @AuthenticationPrincipal UserPrincipal viewer) {
        String clean = code.trim().toUpperCase();
        MaterialGroup group = groupRepository.findByCommonMaterialCode(clean)
                .or(() -> groupRepository.findByProvisionalRef(clean))
                .orElseThrow(() -> new IllegalArgumentException("No national material group found for code: " + code));

        if (!"ACTIVE".equals(group.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Published code not found");
        }
        return ResponseEntity.ok(toDetailsDto(group, viewer));
    }

    /**
     * Validates a national material code using ISO 7064 MOD 37,36.
     */
    @GetMapping("/{code}/validate")
    public ResponseEntity<CodeValidationDto> validateCode(@PathVariable String code) {
        String clean = code.trim().toUpperCase();
        boolean isValid = Iso7064Mod3736.validate(clean);

        CodeValidationDto dto = new CodeValidationDto();
        dto.setCode(clean);
        dto.setValid(isValid);

        // Parse structure: NUMM-SS-FF-CC-NNNNNN-K
        String[] parts = clean.split("-");
        if (parts.length >= 6) {
            dto.setSegment(parts[1]);
            dto.setFamily(parts[2]);
            dto.setCommodityClass(parts[3]);
            dto.setSerial(parts[4]);
            dto.setCheckCharacter(parts[5]);
        }

        if (isValid) {
            dto.setMessage("National material code is valid with verified ISO 7064 MOD 37,36 checksum.");
        } else {
            dto.setMessage("Invalid code: check character mismatch or format error detected.");
        }

        return ResponseEntity.ok(dto);
    }

    /**
     * Search canonical national codes by text or partial code.
     */
    @GetMapping("/search")
    public List<NationalCodeDetailsDto> searchCodes(@RequestParam(required = false) String q,
            @AuthenticationPrincipal UserPrincipal viewer) {
        String query = (q != null && !q.isBlank()) ? q.trim() : "";
        List<MaterialGroup> groups = query.isBlank()
                ? groupRepository.findByStatus("ACTIVE", PageRequest.of(0, 50)).getContent()
                : groupRepository.searchGroups(query, "ACTIVE", PageRequest.of(0, 50)).getContent();

        return groups.stream().map(group -> toDetailsDto(group, viewer)).toList();
    }

    private NationalCodeDetailsDto toDetailsDto(MaterialGroup group, UserPrincipal viewer) {
        NationalCodeDetailsDto dto = new NationalCodeDetailsDto();
        dto.setCommonMaterialCode(group.getCommonMaterialCode());
        dto.setProvisionalRef(group.getProvisionalRef());
        dto.setStatus(group.getStatus());
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
        List<MaterialMapping> mappings = mappingRepository.findByGroup_GroupId(group.getGroupId());
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
            mDto.setMappingStatus(mm.getStatus());
            mDto.setConfidenceScore(mm.getConfidenceScore() != null ? mm.getConfidenceScore().doubleValue() : null);
            mDto.setConfidenceTier(mm.getConfidenceTier());
            members.add(mDto);
        }

        dto.setDistinctCpseCount(distinctCpses.size());
        dto.setMembers(members);

        // Group relations (equivalents & variants)
        List<GroupRelation> relations = groupRelationRepository.findByGroupA_GroupIdOrGroupB_GroupId(group.getGroupId(), group.getGroupId());
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
