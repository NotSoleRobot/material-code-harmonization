package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.MaterialCreateRequest;
import com.sih.materialmaster.dto.MaterialResponse;
import com.sih.materialmaster.entity.Cpse;
import com.sih.materialmaster.entity.Material;
import com.sih.materialmaster.entity.MaterialCategory;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialCategoryRepository;
import com.sih.materialmaster.repository.MaterialRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * FR1: accept material data from CPSEs, and let it be retrieved back.
 * This is the minimal "prove the whole chain works" endpoint for Day 2 -
 * full CRUD (update/delete/list/filter) is intentionally deferred, not
 * required until the review/approval workflow (Day 5) needs it.
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialRepository materialRepository;
    private final CpseRepository cpseRepository;
    private final MaterialCategoryRepository materialCategoryRepository;

    public MaterialController(MaterialRepository materialRepository,
                               CpseRepository cpseRepository,
                               MaterialCategoryRepository materialCategoryRepository) {
        this.materialRepository = materialRepository;
        this.cpseRepository = cpseRepository;
        this.materialCategoryRepository = materialCategoryRepository;
    }

    @PostMapping
    public ResponseEntity<MaterialResponse> createMaterial(@Valid @RequestBody MaterialCreateRequest request) {
        Cpse cpse = cpseRepository.findById(request.getCpseId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No CPSE found with cpseId=" + request.getCpseId()));

        Material material = new Material();
        material.setCpse(cpse);
        material.setCpseMaterialCode(request.getCpseMaterialCode());
        material.setDescription(request.getDescription());
        material.setSpecification(request.getSpecification());
        material.setUnitOfMeasure(request.getUnitOfMeasure());

        if (request.getCategoryId() != null) {
            MaterialCategory category = materialCategoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No category found with categoryId=" + request.getCategoryId()));
            material.setCategory(category);
        }

        Material saved = materialRepository.save(material);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialResponse> getMaterial(@PathVariable Long id) {
        Material material = materialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No material found with materialId=" + id));
        return ResponseEntity.ok(toResponse(material));
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