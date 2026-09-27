package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AuditService;
import com.sih.materialmaster.service.HarmonizationJobService;
import com.sih.materialmaster.service.HarmonizationService;
import jakarta.validation.Valid;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * FR1: Accept material data from CPSEs with strict server-side scoping (W1.1).
 * FR11: Robust CSV bulk ingestion using Apache Commons CSV (BUG-14, BUG-13).
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private static final Map<String, String> CATEGORY_DISPLAY_LABELS = Map.ofEntries(
            Map.entry("PIPES & TUBES", "PIPE"),
            Map.entry("PIPES AND TUBES", "PIPE"),
            Map.entry("PIPES", "PIPE"),
            Map.entry("TUBE", "PIPE"),
            Map.entry("TUBES", "PIPE"),
            Map.entry("INDUSTRIAL VALVES", "VALVE"),
            Map.entry("INDUSTRIAL VALVE", "VALVE"),
            Map.entry("VALVES", "VALVE"),
            Map.entry("PIPE FLANGES", "FLANGE"),
            Map.entry("PIPE FLANGE", "FLANGE"),
            Map.entry("FLANGES", "FLANGE"),
            Map.entry("INDUSTRIAL PUMPS", "PUMP"),
            Map.entry("INDUSTRIAL PUMP", "PUMP"),
            Map.entry("PUMPS", "PUMP"),
            Map.entry("BEARINGS", "BEARING"),
            Map.entry("FASTENERS & STUDS", "FASTENER"),
            Map.entry("FASTENERS AND STUDS", "FASTENER"),
            Map.entry("FASTENERS", "FASTENER"),
            Map.entry("STUDS", "FASTENER"),
            Map.entry("ELECTRIC MOTORS", "MOTOR"),
            Map.entry("ELECTRIC MOTOR", "MOTOR"),
            Map.entry("MOTORS", "MOTOR"),
            Map.entry("ELECTRICAL CABLE", "CABLE"),
            Map.entry("ELECTRICAL CABLES", "CABLE"),
            Map.entry("CABLES", "CABLE")
    );

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
    @Transactional
    public ResponseEntity<BulkUploadResponseDto> uploadCsvMultipart(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean autoHarmonize,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return ResponseEntity.ok(processCsv(reader, autoHarmonize, currentUser));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read CSV file: " + e.getMessage(), e);
        }
    }

    @PostMapping(value = "/bulk-csv", consumes = {"text/csv", "text/plain", MediaType.APPLICATION_JSON_VALUE})
    @Transactional
    public ResponseEntity<BulkUploadResponseDto> uploadCsvText(
            @RequestBody String csvContent,
            @RequestParam(defaultValue = "true") boolean autoHarmonize,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            return ResponseEntity.ok(processCsv(reader, autoHarmonize, currentUser));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse CSV text: " + e.getMessage(), e);
        }
    }

    /**
     * Operator search loop: Request new national code when lookup finds no existing match (W4.2).
     */
    @PostMapping("/request-code")
    @Transactional
    public ResponseEntity<HarmonizationResultDto> requestNewNationalCode(
            @Valid @RequestBody MaterialCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        ResponseEntity<MaterialResponse> created = createMaterial(request, currentUser);
        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(created.getBody().getMaterialId());
        return ResponseEntity.ok(result);
    }

    private BulkUploadResponseDto processCsv(BufferedReader reader, boolean autoHarmonize, UserPrincipal currentUser) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        CSVParser parser = format.parse(reader);
        List<CSVRecord> records = parser.getRecords();

        int lineNum = records.size();
        int imported = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        List<Long> newlyCreatedIds = new ArrayList<>();

        User user = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        Long operatorCpseId = (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole()))
                ? currentUser.getCpseId() : null;

        for (int i = 0; i < records.size(); i++) {
            CSVRecord rec = records.get(i);
            int rowIdx = i + 2;

            String desc = rec.isMapped("description") ? rec.get("description") : (rec.size() > 2 ? rec.get(2) : "");
            if (desc == null || desc.isBlank()) {
                skipped++;
                messages.add("Row " + rowIdx + ": Empty description skipped.");
                continue;
            }

            String cpseName = rec.isMapped("cpse_name") ? rec.get("cpse_name") : (rec.size() > 0 ? rec.get(0) : "");
            String matCode = rec.isMapped("cpse_material_code") ? rec.get("cpse_material_code") : (rec.size() > 1 ? rec.get(1) : "");
            if (matCode == null || matCode.isBlank()) {
                skipped++;
                messages.add("Row " + rowIdx + ": Empty plant material code skipped.");
                continue;
            }
            String spec = rec.isMapped("specification") ? rec.get("specification") : (rec.size() > 3 ? rec.get(3) : "");
            String uom = rec.isMapped("unit_of_measure") ? rec.get("unit_of_measure") : (rec.size() > 4 ? rec.get(4) : "NOS");
            String catName = rec.isMapped("category") ? rec.get("category") : (rec.size() > 5 ? rec.get(5) : "");
            if (catName == null || catName.isBlank()) {
                skipped++;
                messages.add("Row " + rowIdx + ": Empty material category skipped.");
                continue;
            }

            // Enforce CPSE Scoping
            Cpse cpse;
            if (operatorCpseId != null) {
                cpse = cpseRepository.findById(operatorCpseId).orElse(null);
            } else {
                String finalCpseName = (cpseName != null && !cpseName.isBlank()) ? cpseName.trim() : "GENERAL";
                cpse = cpseRepository.findByNameIgnoreCase(finalCpseName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Row " + rowIdx + ": Unknown CPSE '" + finalCpseName + "'"));
            }

            // The implementation plan defines a closed taxonomy of eight leaf
            // categories. An out-of-taxonomy row must not abort valid rows in the
            // same batch, and it must not be guessed into an unrelated category.
            Optional<MaterialCategory> resolvedCategory = resolveCategory(catName);
            if (resolvedCategory.isEmpty()) {
                String normalizedLabel = catName.trim().replaceAll("\\s+", " ");
                skipped++;
                messages.add("Row " + rowIdx + ": Material category '" + normalizedLabel
                        + "' is outside the configured taxonomy; row skipped.");
                continue;
            }
            MaterialCategory category = resolvedCategory.get();

            // Idempotent upsert
            Optional<Material> existingMat = materialRepository.findByCpse_CpseIdAndCpseMaterialCode(cpse.getCpseId(), matCode);
            Material mat = existingMat.orElseGet(Material::new);
            mat.setCpse(cpse);
            mat.setCpseMaterialCode(matCode);
            mat.setDescription(desc.trim());
            mat.setSpecification(spec != null ? spec.trim() : "");
            mat.setUnitOfMeasure(uom != null ? uom.trim().toUpperCase() : "NOS");
            mat.setCategory(category);

            Material saved = materialRepository.save(mat);
            newlyCreatedIds.add(saved.getMaterialId());
            imported++;
        }

        Long jobId = null;
        // Trigger Async Harmonization Job (BUG-13 / W9.5)
        if (autoHarmonize && !newlyCreatedIds.isEmpty()) {
            HarmonizationJob job = jobService.createJob(user, newlyCreatedIds.size());
            jobId = job.getJobId();
            dispatchAfterCommit(job.getJobId(), newlyCreatedIds);
            messages.add("Created async harmonization job #" + job.getJobId() + " for " + newlyCreatedIds.size() + " materials.");
        }

        // Audit bulk ingest event
        auditService.logEvent(user, "BULK_INGEST", "MATERIAL", 0L, null, "Imported " + imported + " rows from CSV (" + skipped + " skipped)");

        return new BulkUploadResponseDto(lineNum, imported, skipped, messages, jobId);
    }

    private void dispatchAfterCommit(Long jobId, List<Long> materialIds) {
        List<Long> committedIds = List.copyOf(materialIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Bulk harmonization must be dispatched from an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobService.processAsync(jobId, committedIds);
            }
        });
    }

    private Optional<MaterialCategory> resolveCategory(String categoryLabel) {
        String normalizedLabel = categoryLabel.trim().replaceAll("\\s+", " ");
        Optional<MaterialCategory> exactCategory =
                materialCategoryRepository.findByNameIgnoreCase(normalizedLabel);
        if (exactCategory.isPresent()) return exactCategory;

        String canonicalName = CATEGORY_DISPLAY_LABELS.get(normalizedLabel.toUpperCase(Locale.ROOT));
        if (canonicalName != null) {
            return Optional.of(materialCategoryRepository.findByNameIgnoreCase(canonicalName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Configured material category '" + canonicalName + "' is missing")));
        }

        return Optional.empty();
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
                material.getCategory() != null ? material.getCategory().getCategoryId() : null,
                material.getCreatedAt()
        );
    }
}
