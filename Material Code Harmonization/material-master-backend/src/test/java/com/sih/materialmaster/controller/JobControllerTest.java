package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.Cpse;
import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.HarmonizationJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    @Mock
    HarmonizationJobService jobService;

    @Test
    void getJobStatusReturnsDiagnosticsAndDetails() {
        HarmonizationJob job = new HarmonizationJob();
        job.setJobId(42L);
        job.setStatus("COMPLETED");

        Cpse cpse = new Cpse();
        cpse.setCpseId(1L);
        job.setCpse(cpse);

        User adminUser = new User();
        adminUser.setUserId(10L);
        adminUser.setEmail("admin@gov.in");
        adminUser.setPasswordHash("pass");
        adminUser.setRole("ADMIN");
        adminUser.setActive(true);
        UserPrincipal admin = new UserPrincipal(adminUser);

        Map<String, Object> map = Map.of(
                "jobId", 42L,
                "status", "COMPLETED",
                "diagnostics", List.of("Row 2: Missing category")
        );
        when(jobService.getJob(42L)).thenReturn(job);
        when(jobService.toJobStatusMap(job)).thenReturn(map);

        JobController controller = new JobController(jobService);
        var resp = controller.getJobStatus(42L, admin);

        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(map, resp.getBody());
    }

    @Test
    void getJobStatusEnforcesOperatorCpseIsolation() {
        HarmonizationJob job = new HarmonizationJob();
        job.setJobId(42L);
        Cpse cpse = new Cpse();
        cpse.setCpseId(1L);
        job.setCpse(cpse);

        Cpse cpse2 = new Cpse();
        cpse2.setCpseId(2L);
        User opUser = new User();
        opUser.setUserId(20L);
        opUser.setEmail("op@ongc.in");
        opUser.setPasswordHash("pass");
        opUser.setRole("OPERATOR");
        opUser.setCpse(cpse2);
        opUser.setActive(true);
        UserPrincipal operatorOther = new UserPrincipal(opUser);
        when(jobService.getJob(42L)).thenReturn(job);

        JobController controller = new JobController(jobService);
        assertThrows(AccessDeniedException.class, () -> controller.getJobStatus(42L, operatorOther));
    }

    @Test
    void streamJobEventsReturnsSseEmitter() {
        HarmonizationJob job = new HarmonizationJob();
        job.setJobId(42L);
        Cpse cpse = new Cpse();
        cpse.setCpseId(1L);
        job.setCpse(cpse);

        User opUser = new User();
        opUser.setUserId(20L);
        opUser.setEmail("op@bhel.in");
        opUser.setPasswordHash("pass");
        opUser.setRole("OPERATOR");
        opUser.setCpse(cpse);
        opUser.setActive(true);
        UserPrincipal operatorSame = new UserPrincipal(opUser);
        when(jobService.getJob(42L)).thenReturn(job);
        SseEmitter emitter = new SseEmitter();
        when(jobService.subscribe(42L)).thenReturn(emitter);

        JobController controller = new JobController(jobService);
        org.springframework.http.ResponseEntity<SseEmitter> response = controller.streamJobEvents(42L, operatorSame);
        assertSame(emitter, response.getBody());
        org.junit.jupiter.api.Assertions.assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        verify(jobService).subscribe(42L);
    }
}
