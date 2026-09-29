package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.Cpse;
import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.*;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AuditService;
import com.sih.materialmaster.service.HarmonizationJobService;
import com.sih.materialmaster.service.HarmonizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialControllerTest {

    @Mock MaterialRepository materialRepository;
    @Mock CpseRepository cpseRepository;
    @Mock MaterialCategoryRepository categoryRepository;
    @Mock MaterialMappingRepository mappingRepository;
    @Mock UserRepository userRepository;
    @Mock HarmonizationService harmonizationService;
    @Mock HarmonizationJobService jobService;
    @Mock AuditService auditService;

    @Test
    void multipartCsvIsSpooledAndAcceptedBeforeBackgroundProcessing() throws Exception {
        Cpse cpse = new Cpse();
        cpse.setCpseId(11L);
        User user = new User();
        user.setUserId(7L);
        user.setRole("OPERATOR");
        user.setCpse(cpse);
        user.setActive(true);
        HarmonizationJob job = new HarmonizationJob();
        job.setJobId(99L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jobService.createIngestionJob(user)).thenReturn(job);

        MaterialController controller = new MaterialController(
                materialRepository, cpseRepository, categoryRepository, mappingRepository,
                userRepository, harmonizationService, jobService, auditService);
        MockMultipartFile csv = new MockMultipartFile("file", "items.csv", "text/csv",
                "cpse_material_code,description,category\nA-1,Safety helmet,SAFETY\n".getBytes());

        var response = controller.uploadCsvMultipart(csv, true, new UserPrincipal(user));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("QUEUED", response.getBody().getStatus());
        assertEquals(99L, response.getBody().getJobId());
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(jobService).processCsvAsync(eq(99L), pathCaptor.capture(), eq(11L), eq(7L), eq(true));
        Files.deleteIfExists(pathCaptor.getValue());
    }
}
