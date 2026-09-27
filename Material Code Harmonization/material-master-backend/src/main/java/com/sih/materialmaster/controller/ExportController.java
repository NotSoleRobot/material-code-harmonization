package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;
    private final UserRepository userRepository;

    public ExportController(ExportService exportService, UserRepository userRepository) {
        this.exportService = exportService;
        this.userRepository = userRepository;
    }

    /**
     * Cross-reference index export. For OPERATOR, strictly locked to own CPSE (W5.1).
     */
    @GetMapping("/cross-reference")
    public ResponseEntity<StreamingResponseBody> exportCrossReference(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) Long cpseId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        Long effectiveCpseId = (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole()))
                ? currentUser.getCpseId()
                : cpseId;

        User user = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String ext = "xlsx".equalsIgnoreCase(format) ? "xlsx" : "csv";
        String cpseLabel = (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole()))
                ? currentUser.getCpseName() : "ALL";
        String filename = String.format("NUMM_crossref_%s_%s.%s", cpseLabel, dateStr, ext);

        MediaType mediaType = "xlsx".equalsIgnoreCase(format)
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv");

        StreamingResponseBody stream = outputStream -> exportService.streamCrossReference(effectiveCpseId, format, outputStream, user);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(stream);
    }

    /**
     * Full Canonical Master Catalog export (Senior Reviewer / Admin only) (W5.1).
     */
    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportMasterCatalog(
            @RequestParam(defaultValue = "csv") String format,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User user = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String ext = "xlsx".equalsIgnoreCase(format) ? "xlsx" : "csv";
        String filename = String.format("NUMM_Master_Catalog_%s.%s", dateStr, ext);

        MediaType mediaType = "xlsx".equalsIgnoreCase(format)
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv");

        StreamingResponseBody stream = outputStream -> exportService.streamMasterCatalog(format, outputStream, user);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(stream);
    }

    /**
     * SAP ERP mapping integration template export (W5.1 / FR11).
     */
    @GetMapping("/erp-template")
    public ResponseEntity<StreamingResponseBody> exportSapErpTemplate(
            @RequestParam(required = false) Long cpseId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        Long effectiveCpseId = (currentUser != null && "OPERATOR".equalsIgnoreCase(currentUser.getRole()))
                ? currentUser.getCpseId()
                : cpseId;

        User user = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String filename = String.format("SAP_NUMM_Mapping_%s.csv", dateStr);

        StreamingResponseBody stream = outputStream -> exportService.streamSapErpTemplate(effectiveCpseId, outputStream, user);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(stream);
    }
}
