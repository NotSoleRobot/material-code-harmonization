package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AuditService;
import com.sih.materialmaster.service.HarmonizationJobService;
import com.sih.materialmaster.service.HarmonizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * FR1: Accept material data from CPSEs with strict server-side scoping (W1.1).
 * FR11: Robust CSV bulk ingestion using Apache Commons CSV (BUG-14, BUG-13).
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialRepository materialRepository;
    private final CpseRepository cpseRepository;
    private final MaterialCategoryRepository materialCategoryRepository;
    private final MaterialMappingRepository mappingRepository;
    private final UserRepository userRepository;
    private final HarmonizationService harmonizationService;
    private final HarmonizationJobService jobService;
    private final AuditService auditService;

    public MaterialController(MaterialRepository materialRepository,
                              CpseRepository cpseRepository,
                              MaterialCategoryRepository materialCategoryRepository,
                              MaterialMappingRepository mappingRepository,
                              UserRepository userRepository,
                              HarmonizationService harmonizationService,
                              HarmonizationJobService jobService,
                              AuditService auditService) {
        this.materialRepository = materialRepository;
        this.cpseRepository = cpseRepository;
        this.materialCategoryRepository = materialCategoryRepository;
        this.mappingRepository = mappingRepository;
        this.userRepository = userRepository;
        this.harmonizationService = harmonizationService;
        this.jobService = jobService;
        this.auditService = auditService;
    }

    /**
     * FR1: Retrieve materials. For OPERATOR, strictly scoped to currentUser.cpseId (W1.1 #6).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<MaterialResponse> getAllMaterials(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<Material> materials;
        if (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole())) {
            if (currentUser.getCpseId() == null) {
                throw new AccessDeniedException("Operator account is not assigned to a CPSE");
            }
            materials = materialRepository.findByCpse_CpseId(currentUser.getCpseId());
        } else {
            materials = materialRepository.findAll();
        }

        return materials.stream().map(this::toResponse).toList();
    }

    /**
     * FR1: Ingest single material record. Forces cpse_id to currentUser's CPSE if OPERATOR.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<MaterialResponse> createMaterial(
            @Valid @RequestBody MaterialCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        Long effectiveCpseId;
        if (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole())) {
            effectiveCpseId = currentUser.getCpseId(); // Ignore any client-provided CPSE (W1.1 #6)
        } else {
            effectiveCpseId = request.getCpseId();
        }

        if (effectiveCpseId == null) {
            throw new IllegalArgumentException("CPSE ID is required for material ingestion.");
        }

        Cpse cpse = cpseRepository.findById(effectiveCpseId)
                .orElseThrow(() -> new IllegalArgumentException("No CPSE found with cpseId=" + effectiveCpseId));

        Material material = new Material();
        material.setCpse(cpse);
        material.setCpseMaterialCode(request.getCpseMaterialCode().trim());
        material.setDescription(request.getDescription().trim());
        material.setSpecification(request.getSpecification() != null ? request.getSpecification().trim() : "");
        material.setUnitOfMeasure(request.getUnitOfMeasure() != null ? request.getUnitOfMeasure().trim().toUpperCase() : "NOS");
        material.setNominalPrice(request.getNominalPrice());

        if (request.getCategoryId() != null) {
            MaterialCategory category = materialCategoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("No category found with categoryId=" + request.getCategoryId()));
            material.setCategory(category);
        }

        Material saved = materialRepository.save(material);
        harmonizationService.extractAttributesForMaterials(List.of(saved.getMaterialId()));

        User author = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        auditService.logEvent(author, "MATERIAL_INGESTED", "MATERIAL", saved.getMaterialId(), null,
                "Ingested material " + saved.getCpseMaterialCode() + " (" + cpse.getName() + ")");

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    /**
     * FR1: Retrieve material by ID. Strictly enforces CPSE isolation with 403 Forbidden on violation (W1.1 #6).
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<MaterialResponse> getMaterial(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No material found with materialId=" + id));

        if (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole())) {
            if (!material.getCpse().getCpseId().equals(currentUser.getCpseId())) {
                throw new AccessDeniedException("Access Denied: Material #" + id + " belongs to a different CPSE.");
            }
        }

        return ResponseEntity.ok(toResponse(material));
    }

    @GetMapping("/unmatched")
    @Transactional(readOnly = true)
    public List<MaterialResponse> getUnmatchedMaterials(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<Material> unmatched = materialRepository.findUnmatched();
        if (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole())) {
            unmatched = unmatched.stream()
                    .filter(m -> m.getCpse().getCpseId().equals(currentUser.getCpseId()))
                    .toList();
        }
        return unmatched.stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/harmonize")
    public ResponseEntity<HarmonizationResultDto> triggerHarmonization(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal currentUser) {
        getMaterial(id, currentUser);
        return ResponseEntity.ok(harmonizationService.harmonizeMaterial(id));
    }

    /**
     * FR11: Robust CSV bulk ingestion using RFC 4180 Apache Commons CSV (BUG-14, BUG-13).
     */
    @PostMapping(value = "/bulk-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BulkUploadResponseDto> uploadCsvMultipart(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean autoHarmonize,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        try (var input = file.getInputStream()) {
            return enqueueCsv(input, autoHarmonize, currentUser);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    @PostMapping(value = "/bulk-csv", consumes = {"text/csv", "text/plain", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<BulkUploadResponseDto> uploadCsvText(
            HttpServletRequest request,
            @RequestParam(defaultValue = "true") boolean autoHarmonize,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        try (var input = request.getInputStream()) {
            return enqueueCsv(input, autoHarmonize, currentUser);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse CSV text: " + e.getMessage(), e);
        }
    }

    private ResponseEntity<BulkUploadResponseDto> enqueueCsv(
            java.io.InputStream input,
            boolean autoHarmonize,
            UserPrincipal currentUser) throws java.io.IOException {
        if (currentUser == null) {
            throw new AccessDeniedException("Authentication is required for CSV ingestion");
        }
        Path spoolFile = Files.createTempFile("numm-ingestion-", ".csv");
        try {
            Files.copy(input, spoolFile, StandardCopyOption.REPLACE_EXISTING);
            User user = userRepository.findById(currentUser.getUserId())
                    .orElseThrow(() -> new AccessDeniedException("Authenticated user no longer exists"));
            HarmonizationJob job = jobService.createIngestionJob(user);
            jobService.processCsvAsync(
                    job.getJobId(), spoolFile, currentUser.getCpseId(), currentUser.getUserId(), autoHarmonize);
            BulkUploadResponseDto response = new BulkUploadResponseDto(
                    0, 0, 0,
                    List.of("File accepted for background ingestion and harmonization."),
                    job.getJobId(), "QUEUED",
                    "File accepted for background ingestion and harmonization.");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (RuntimeException | java.io.IOException ex) {
            Files.deleteIfExists(spoolFile);
            throw ex;
        }
    }

    private MaterialResponse toResponse(Material material) {
        return new MaterialResponse(
                material.getMaterialId(),
                material.getCpse().getCpseId(),
                material.getCpse().getName(),
                material.getCpseMaterialCode(),
                material.getDescription(),
                material.getSpecification(),
                material.getUnitOfMeasure(),
                material.getNominalPrice(),
                material.getCategory() != null ? material.getCategory().getCategoryId() : null,
                material.getCreatedAt()
        );
    }
}
