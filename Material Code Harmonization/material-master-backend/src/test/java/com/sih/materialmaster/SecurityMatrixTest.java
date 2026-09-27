package com.sih.materialmaster;

import com.sih.materialmaster.config.SecurityConfig;
import com.sih.materialmaster.security.CustomUserDetailsService;
import com.sih.materialmaster.security.JwtAuthenticationFilter;
import com.sih.materialmaster.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityMatrixTest.MatrixProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityMatrixTest.MatrixProbeController.class})
class SecurityMatrixTest {

    @Autowired MockMvc mvc;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean CustomUserDetailsService userDetailsService;

    @RestController
    public static class MatrixProbeController {
        @RequestMapping({"/api/auth/login", "/error", "/actuator/health", "/api/auth/demo-accounts",
                "/api/auth/me", "/api/auth/logout", "/api/admin/users", "/api/analytics/stats",
                "/api/dashboard/stats", "/api/mappings/audit/verify", "/api/mappings/1/supersede",
                "/api/mappings/bulk-approve", "/api/mappings/1/approve", "/api/mappings/1/reject",
                "/api/mappings/1/edit", "/api/mappings/1", "/api/groups/publishable",
                "/api/groups/1/mint", "/api/codes/NUMM-40-14-07-000001-X",
                "/api/harmonization/compare", "/api/harmonization/harmonize-all",
                "/api/harmonization/1", "/api/materials/1", "/api/jobs/1",
                "/api/export/catalog", "/api/export/cross-reference", "/api/not-declared"})
        public ResponseEntity<Void> ok() { return ResponseEntity.ok().build(); }

        @PutMapping("/api/analytics/assumptions/1")
        public ResponseEntity<Void> assumption() { return ResponseEntity.ok().build(); }
    }

    @Test void row01_publicEndpointsArePermitted() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void row02_demoAccountsGetIsPublic() throws Exception {
        mvc.perform(get("/api/auth/demo-accounts")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/demo-accounts")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser void row03_sessionEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk());
    }
    @Test void row03_sessionEndpointsRejectAnonymous() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="ADMIN") void row04_adminAreaIsAdminOnly() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row04_adminAreaRejectsReviewer() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void row05_assumptionsPutIsAdminOnly() throws Exception {
        mvc.perform(put("/api/analytics/assumptions/1")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row05_assumptionsPutRejectsSenior() throws Exception {
        mvc.perform(put("/api/analytics/assumptions/1")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row06_analyticsReadAllowsSenior() throws Exception {
        mvc.perform(get("/api/dashboard/stats")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="OPERATOR") void row06_analyticsReadRejectsOperator() throws Exception {
        mvc.perform(get("/api/analytics/stats")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void row07_auditReadAllowsAdmin() throws Exception {
        mvc.perform(get("/api/mappings/audit/verify")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="OPERATOR") void row07_auditReadRejectsOperator() throws Exception {
        mvc.perform(get("/api/mappings/audit/verify")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row08_supersedeAllowsSenior() throws Exception {
        mvc.perform(post("/api/mappings/1/supersede")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row08_supersedeRejectsReviewer() throws Exception {
        mvc.perform(post("/api/mappings/1/supersede")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="REVIEWER") void row09_bulkApproveAllowsReviewer() throws Exception {
        mvc.perform(post("/api/mappings/bulk-approve")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="ADMIN") void row09_bulkApproveRejectsAdmin() throws Exception {
        mvc.perform(post("/api/mappings/bulk-approve")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="REVIEWER") void row10_mappingDecisionAllowsReviewer() throws Exception {
        mvc.perform(post("/api/mappings/1/approve")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="ADMIN") void row10_mappingDecisionRejectsAdmin() throws Exception {
        mvc.perform(post("/api/mappings/1/reject")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void row11_mappingReadAllowsAdmin() throws Exception {
        mvc.perform(get("/api/mappings/1")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="OPERATOR") void row11_mappingReadRejectsOperator() throws Exception {
        mvc.perform(get("/api/mappings/1")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row12_publishableAllowsSenior() throws Exception {
        mvc.perform(get("/api/groups/publishable")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row12_publishableRejectsReviewer() throws Exception {
        mvc.perform(get("/api/groups/publishable")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void row13_mintRejectsAdmin() throws Exception {
        mvc.perform(post("/api/groups/1/mint")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row13_mintAllowsSenior() throws Exception {
        mvc.perform(post("/api/groups/1/mint")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="OPERATOR") void row14_codesReadAllowsAuthenticated() throws Exception {
        mvc.perform(get("/api/codes/NUMM-40-14-07-000001-X")).andExpect(status().isOk());
    }
    @Test void row14_codesReadRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/codes/NUMM-40-14-07-000001-X")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="OPERATOR") void row15_compareAllowsEveryRole() throws Exception {
        mvc.perform(post("/api/harmonization/compare")).andExpect(status().isOk());
    }
    @Test void row15_compareRejectsAnonymous() throws Exception {
        mvc.perform(post("/api/harmonization/compare")).andExpect(status().isUnauthorized());
    }
    @Test @WithMockUser(roles="ADMIN") void row16_harmonizeAllIsAdminOnly() throws Exception {
        mvc.perform(post("/api/harmonization/harmonize-all")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="OPERATOR") void row16_harmonizeAllRejectsOperator() throws Exception {
        mvc.perform(post("/api/harmonization/harmonize-all")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="OPERATOR") void row17_singleHarmonizeAllowsOperator() throws Exception {
        mvc.perform(post("/api/harmonization/1")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row17_singleHarmonizeRejectsReviewer() throws Exception {
        mvc.perform(post("/api/harmonization/1")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="OPERATOR") void row18_materialsAndJobsAllowOperator() throws Exception {
        mvc.perform(get("/api/materials/1")).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/1")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row18_materialsAndJobsRejectReviewer() throws Exception {
        mvc.perform(get("/api/materials/1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/jobs/1")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="SENIOR_REVIEWER") void row19_catalogExportAllowsSenior() throws Exception {
        mvc.perform(get("/api/export/catalog")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row19_catalogExportRejectsReviewer() throws Exception {
        mvc.perform(get("/api/export/catalog")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="OPERATOR") void row20_otherExportsAllowOperator() throws Exception {
        mvc.perform(get("/api/export/cross-reference")).andExpect(status().isOk());
    }
    @Test @WithMockUser(roles="REVIEWER") void row20_otherExportsRejectReviewer() throws Exception {
        mvc.perform(get("/api/export/cross-reference")).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(roles="ADMIN") void row21_unlistedEndpointsAreDenied() throws Exception {
        mvc.perform(get("/api/not-declared")).andExpect(status().isForbidden());
    }
}
