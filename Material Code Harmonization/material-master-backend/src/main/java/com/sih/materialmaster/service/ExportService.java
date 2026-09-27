package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ExportService {

    private final MaterialRepository materialRepository;
    private final MaterialMappingRepository mappingRepository;
    private final MaterialGroupRepository groupRepository;
    private final AuditService auditService;

    public ExportService(MaterialRepository materialRepository,
                         MaterialMappingRepository mappingRepository,
                         MaterialGroupRepository groupRepository,
                         AuditService auditService) {
        this.materialRepository = materialRepository;
        this.mappingRepository = mappingRepository;
        this.groupRepository = groupRepository;
        this.auditService = auditService;
    }

    /**
     * Streams cross-reference mapping export (CSV or XLSX) with server-enforced CPSE scoping (W5.1).
     */
    @Transactional
    public int streamCrossReference(Long cpseId, String format, OutputStream outputStream, User user) throws IOException {
        List<Material> materials = (cpseId != null)
                ? materialRepository.findByCpse_CpseId(cpseId)
                : materialRepository.findAll();

        if ("xlsx".equalsIgnoreCase(format)) {
            writeCrossReferenceXlsx(materials, outputStream);
        } else {
            writeCrossReferenceCsv(materials, outputStream);
        }

        auditService.logEvent(user, "EXPORT_GENERATED", "EXPORT", 0L, null,
                "Exported cross-reference catalog (" + format.toUpperCase() + ", " + materials.size() + " rows, cpse: " + (cpseId != null ? cpseId : "ALL") + ")");

        return materials.size();
    }

    /**
     * Streams canonical master catalog export for Reviewer and Admin (W5.1).
     */
    @Transactional
    public int streamMasterCatalog(String format, OutputStream outputStream, User user) throws IOException {
        List<MaterialGroup> groups = groupRepository.findAll();

        if ("xlsx".equalsIgnoreCase(format)) {
            writeMasterCatalogXlsx(groups, outputStream);
        } else {
            writeMasterCatalogCsv(groups, outputStream);
        }

        auditService.logEvent(user, "EXPORT_GENERATED", "EXPORT", 0L, null,
                "Exported canonical master catalog (" + format.toUpperCase() + ", " + groups.size() + " groups)");

        return groups.size();
    }

    /**
     * Streams the WP10 SAP ERP template: MATNR, MAKTX, MEINS, MATKL, NUMM_CODE.
     */
    @Transactional
    public int streamSapErpTemplate(Long cpseId, OutputStream outputStream, User user) throws IOException {
        List<Material> materials = (cpseId != null)
                ? materialRepository.findByCpse_CpseId(cpseId)
                : materialRepository.findAll();

        // UTF-8 BOM is intentional: Windows Excel otherwise commonly misdetects CSV encoding.
        outputStream.write(0xEF);
        outputStream.write(0xBB);
        outputStream.write(0xBF);
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader("MATNR", "MAKTX", "MEINS", "MATKL", "NUMM_CODE").build())) {

            int count = 0;
            for (Material m : materials) {
                Optional<MaterialMapping> active = mappingRepository.findActiveByMaterialId(m.getMaterialId());
                String nummCode = "";
                String stdDesc = "";
                String materialGroup = m.getCategory() != null ? m.getCategory().getName() : "GENERAL";

                if (active.isPresent() && active.get().getGroup() != null) {
                    MaterialGroup g = active.get().getGroup();
                    nummCode = g.getCommonMaterialCode() != null ? g.getCommonMaterialCode() : g.getProvisionalRef();
                    stdDesc = g.getStandardizedDescription();
                }

                printer.printRecord(
                        m.getCpseMaterialCode(),
                        stdDesc,
                        m.getUnitOfMeasure() != null ? m.getUnitOfMeasure() : "NOS",
                        materialGroup,
                        nummCode
                );
                count++;
            }
            printer.flush();

            auditService.logEvent(user, "EXPORT_GENERATED", "EXPORT", 0L, null,
                    "Exported SAP ERP mapping template (" + count + " records)");

            return count;
        }
    }

    private void writeCrossReferenceCsv(List<Material> materials, OutputStream outputStream) throws IOException {
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader(
                        "CPSE", "Plant_Material_Code", "Raw_Description", "Specification",
                        "National_Material_Code", "Provisional_Ref", "Standardized_Description",
                        "Commodity_Class", "UOM", "Mapping_Status", "Confidence_Tier", "Confidence_Score"
                ).build())) {

            for (Material m : materials) {
                Optional<MaterialMapping> active = mappingRepository.findActiveByMaterialId(m.getMaterialId());
                String nummCode = "";
                String provRef = "";
                String stdDesc = "";
                String catName = m.getCategory() != null ? m.getCategory().getName() : "GENERAL";
                String status = "UNMAPPED";
                String tier = "NONE";
                String score = "0.0";

                if (active.isPresent() && active.get().getGroup() != null) {
                    MaterialGroup g = active.get().getGroup();
                    nummCode = g.getCommonMaterialCode() != null ? g.getCommonMaterialCode() : "";
                    provRef = g.getProvisionalRef();
                    stdDesc = g.getStandardizedDescription();
                    status = active.get().getStatus();
                    tier = active.get().getConfidenceTier();
                    score = active.get().getConfidenceScore() != null ? active.get().getConfidenceScore().toString() : "0.0";
                }

                printer.printRecord(
                        m.getCpse() != null ? m.getCpse().getName() : "Unknown",
                        m.getCpseMaterialCode(),
                        m.getDescription(),
                        m.getSpecification() != null ? m.getSpecification() : "",
                        nummCode,
                        provRef,
                        stdDesc,
                        catName,
                        m.getUnitOfMeasure() != null ? m.getUnitOfMeasure() : "NOS",
                        status,
                        tier,
                        score
                );
            }
            printer.flush();
        }
    }

    private void writeCrossReferenceXlsx(List<Material> materials, OutputStream outputStream) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("Cross Reference Index");
            String[] headers = {
                    "CPSE", "Plant Code", "Raw Description", "Specification",
                    "National Code", "Provisional Ref", "Standardized Description",
                    "Category", "UOM", "Status", "Confidence Tier", "Score"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            int rowIdx = 1;
            for (Material m : materials) {
                Optional<MaterialMapping> active = mappingRepository.findActiveByMaterialId(m.getMaterialId());
                Row row = sheet.createRow(rowIdx++);

                row.createCell(0).setCellValue(m.getCpse() != null ? m.getCpse().getName() : "Unknown");
                row.createCell(1).setCellValue(m.getCpseMaterialCode());
                row.createCell(2).setCellValue(m.getDescription());
                row.createCell(3).setCellValue(m.getSpecification() != null ? m.getSpecification() : "");

                if (active.isPresent() && active.get().getGroup() != null) {
                    MaterialGroup g = active.get().getGroup();
                    row.createCell(4).setCellValue(g.getCommonMaterialCode() != null ? g.getCommonMaterialCode() : "");
                    row.createCell(5).setCellValue(g.getProvisionalRef());
                    row.createCell(6).setCellValue(g.getStandardizedDescription());
                    row.createCell(7).setCellValue(g.getCategory() != null ? g.getCategory().getName() : "GENERAL");
                    row.createCell(8).setCellValue(m.getUnitOfMeasure() != null ? m.getUnitOfMeasure() : "NOS");
                    row.createCell(9).setCellValue(active.get().getStatus());
                    row.createCell(10).setCellValue(active.get().getConfidenceTier());
                    row.createCell(11).setCellValue(active.get().getConfidenceScore() != null ? active.get().getConfidenceScore().doubleValue() : 0.0);
                } else {
                    row.createCell(4).setCellValue("");
                    row.createCell(5).setCellValue("");
                    row.createCell(6).setCellValue("");
                    row.createCell(7).setCellValue(m.getCategory() != null ? m.getCategory().getName() : "GENERAL");
                    row.createCell(8).setCellValue(m.getUnitOfMeasure() != null ? m.getUnitOfMeasure() : "NOS");
                    row.createCell(9).setCellValue("UNMAPPED");
                    row.createCell(10).setCellValue("NONE");
                    row.createCell(11).setCellValue(0.0);
                }
            }

            workbook.write(outputStream);
            workbook.dispose();
        }
    }

    private void writeMasterCatalogCsv(List<MaterialGroup> groups, OutputStream outputStream) throws IOException {
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader(
                        "National_Material_Code", "Provisional_Ref", "Status", "Standardized_Description",
                        "Specification", "UOM", "Category", "UNSPSC_Segment", "UNSPSC_Family", "UNSPSC_Class",
                        "Linked_CPSE_Count", "Total_Member_Materials"
                ).build())) {

            for (MaterialGroup g : groups) {
                List<MaterialMapping> mappings = mappingRepository.findByGroup_GroupId(g.getGroupId());
                Set<String> cpseSet = new HashSet<>();
                for (MaterialMapping mm : mappings) {
                    if (mm.getMaterial() != null && mm.getMaterial().getCpse() != null) {
                        cpseSet.add(mm.getMaterial().getCpse().getName());
                    }
                }

                MaterialCategory cat = g.getCategory();
                printer.printRecord(
                        g.getCommonMaterialCode() != null ? g.getCommonMaterialCode() : "",
                        g.getProvisionalRef(),
                        g.getStatus(),
                        g.getStandardizedDescription(),
                        g.getStandardizedSpecification() != null ? g.getStandardizedSpecification() : "",
                        g.getStandardizedUom() != null ? g.getStandardizedUom() : "NOS",
                        cat != null ? cat.getName() : "GENERAL",
                        cat != null ? cat.getCodeSegment() : "",
                        cat != null ? cat.getCodeFamily() : "",
                        cat != null ? cat.getCodeClass() : "",
                        cpseSet.size(),
                        mappings.size()
                );
            }
            printer.flush();
        }
    }

    private void writeMasterCatalogXlsx(List<MaterialGroup> groups, OutputStream outputStream) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("National Master Catalog");
            String[] headers = {
                    "National Code", "Provisional Ref", "Status", "Standardized Description",
                    "Specification", "UOM", "Category", "Segment", "Family", "Class", "CPSE Count", "Total Mappings"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            int rowIdx = 1;
            for (MaterialGroup g : groups) {
                List<MaterialMapping> mappings = mappingRepository.findByGroup_GroupId(g.getGroupId());
                Set<String> cpseSet = new HashSet<>();
                for (MaterialMapping mm : mappings) {
                    if (mm.getMaterial() != null && mm.getMaterial().getCpse() != null) {
                        cpseSet.add(mm.getMaterial().getCpse().getName());
                    }
                }

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(g.getCommonMaterialCode() != null ? g.getCommonMaterialCode() : "");
                row.createCell(1).setCellValue(g.getProvisionalRef());
                row.createCell(2).setCellValue(g.getStatus());
                row.createCell(3).setCellValue(g.getStandardizedDescription());
                row.createCell(4).setCellValue(g.getStandardizedSpecification() != null ? g.getStandardizedSpecification() : "");
                row.createCell(5).setCellValue(g.getStandardizedUom() != null ? g.getStandardizedUom() : "NOS");

                MaterialCategory cat = g.getCategory();
                row.createCell(6).setCellValue(cat != null ? cat.getName() : "GENERAL");
                row.createCell(7).setCellValue(cat != null && cat.getCodeSegment() != null ? cat.getCodeSegment() : "");
                row.createCell(8).setCellValue(cat != null && cat.getCodeFamily() != null ? cat.getCodeFamily() : "");
                row.createCell(9).setCellValue(cat != null && cat.getCodeClass() != null ? cat.getCodeClass() : "");
                row.createCell(10).setCellValue(cpseSet.size());
                row.createCell(11).setCellValue(mappings.size());
            }

            workbook.write(outputStream);
            workbook.dispose();
        }
    }
}
