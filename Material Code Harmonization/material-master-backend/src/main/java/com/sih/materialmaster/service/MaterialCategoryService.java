package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.MaterialCategory;
import com.sih.materialmaster.repository.MaterialCategoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;

@Service
public class MaterialCategoryService {

    public static final String GENERAL_MRO = "GENERAL_MRO";

    private static final Map<String, String> DISPLAY_LABELS = Map.ofEntries(
            Map.entry("PIPES & TUBES", "PIPE"), Map.entry("PIPES AND TUBES", "PIPE"),
            Map.entry("PIPES", "PIPE"), Map.entry("TUBE", "PIPE"), Map.entry("TUBES", "PIPE"),
            Map.entry("INDUSTRIAL VALVES", "VALVE"), Map.entry("INDUSTRIAL VALVE", "VALVE"),
            Map.entry("VALVES", "VALVE"), Map.entry("PIPE FLANGES", "FLANGE"),
            Map.entry("PIPE FLANGE", "FLANGE"), Map.entry("FLANGES", "FLANGE"),
            Map.entry("INDUSTRIAL PUMPS", "PUMP"), Map.entry("INDUSTRIAL PUMP", "PUMP"),
            Map.entry("PUMPS", "PUMP"), Map.entry("BEARINGS", "BEARING"),
            Map.entry("FASTENERS & STUDS", "FASTENER"), Map.entry("FASTENERS AND STUDS", "FASTENER"),
            Map.entry("FASTENERS", "FASTENER"), Map.entry("STUDS", "FASTENER"),
            Map.entry("ELECTRIC MOTORS", "MOTOR"), Map.entry("ELECTRIC MOTOR", "MOTOR"),
            Map.entry("MOTORS", "MOTOR"), Map.entry("ELECTRICAL CABLE", "CABLE"),
            Map.entry("ELECTRICAL CABLES", "CABLE"), Map.entry("CABLES", "CABLE")
    );

    private final MaterialCategoryRepository repository;

    public MaterialCategoryService(MaterialCategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MaterialCategory resolveOpenDomain(String categoryLabel) {
        String normalized = categoryLabel == null ? "" : categoryLabel.trim().replaceAll("\\s+", " ");
        if (!normalized.isBlank()) {
            var exact = repository.findByNameIgnoreCase(normalized);
            if (exact.isPresent()) return exact.get();

            String canonical = DISPLAY_LABELS.get(normalized.toUpperCase(Locale.ROOT));
            if (canonical != null) {
                return repository.findByNameIgnoreCase(canonical)
                        .orElseThrow(() -> new IllegalStateException(
                                "Configured material category '" + canonical + "' is missing"));
            }
        }
        return repository.findByNameIgnoreCase(GENERAL_MRO).orElseGet(this::createGeneralCategory);
    }

    private MaterialCategory createGeneralCategory() {
        MaterialCategory general = new MaterialCategory();
        general.setName(GENERAL_MRO);
        general.setLevel(3);
        general.setCodeSegment("99");
        general.setCodeFamily("99");
        general.setCodeClass("99");
        general.setDescription("Open-domain industrial MRO materials outside configured specialist taxonomies");
        general.setCustom(true);
        try {
            return repository.saveAndFlush(general);
        } catch (DataIntegrityViolationException concurrentInsert) {
            return repository.findByNameIgnoreCase(GENERAL_MRO).orElseThrow(() -> concurrentInsert);
        }
    }
}
