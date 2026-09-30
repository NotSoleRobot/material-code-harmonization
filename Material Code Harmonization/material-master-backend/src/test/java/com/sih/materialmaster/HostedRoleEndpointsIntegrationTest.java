package com.sih.materialmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.entity.AuditTrail;
import com.sih.materialmaster.entity.Cpse;
import com.sih.materialmaster.entity.Material;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.AuditTrailRepository;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialRepository;
import com.sih.materialmaster.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
        "spring.datasource.url=jdbc:h2:mem:hosted_role_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=KEY,VALUE",
        "app.cors.allowed-origins=https://numm-frontend.onrender.com"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class HostedRoleEndpointsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired CpseRepository cpseRepository;
    @Autowired UserRepository userRepository;
    @Autowired MaterialRepository materialRepository;
    @Autowired AuditTrailRepository auditTrailRepository;

    @BeforeEach
    void seedDetachedRelationshipFixtures() {
        auditTrailRepository.deleteAll();
        materialRepository.deleteAll();
        userRepository.deleteAll();
        cpseRepository.deleteAll();

        Cpse ongc = new Cpse();
        ongc.setName("ONGC");
        ongc.setSector("Oil and Gas");
        ongc = cpseRepository.save(ongc);

        User admin = saveUser("System Administrator", "admin@numm.gov.in", "admin123", "ADMIN", null);
        saveUser("Senior Reviewer", "senior.reviewer@numm.gov.in", "reviewer123", "SENIOR_REVIEWER", null);
        saveUser("Mechanical Reviewer", "reviewer.mech@numm.gov.in", "reviewer123", "REVIEWER", ongc);
        saveUser("ONGC Operator", "operator@ongc.co.in", "operator123", "OPERATOR", ongc);

        Material material = new Material();
        material.setCpse(ongc);
        material.setCpseMaterialCode("ONGC-PIPE-001");
        material.setDescription("CARBON STEEL PIPE DN50 SCH40");
        material.setSpecification("ASTM A106 GR B");
        material.setUnitOfMeasure("M");
        material.setNominalPrice(new BigDecimal("1250.00"));
        materialRepository.save(material);

        AuditTrail audit = new AuditTrail();
        audit.setUser(admin);
        audit.setAction("MATERIAL_INGESTED");
        audit.setEntityType("MATERIAL");
        audit.setEntityId(material.getMaterialId());
        audit.setPrevHash("0".repeat(64));
        audit.setRowHash("A".repeat(64));
        audit.setTimestamp(LocalDateTime.now());
        auditTrailRepository.save(audit);
    }

    @Test
    void roleScreensResolveLazyRelationshipsAfterRepositoryTransactionsClose() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header("Origin", "https://numm-frontend.onrender.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://numm-frontend.onrender.com"));

        mvc.perform(get("/api/auth/demo-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem("operator@ongc.co.in")));

        String operatorToken = login("operator@ongc.co.in", "operator123");
        mvc.perform(get("/api/materials").header("Authorization", bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cpseName").value("ONGC"))
                .andExpect(jsonPath("$[0].nominalPrice").value(1250.00));

        login("reviewer.mech@numm.gov.in", "reviewer123");

        String adminToken = login("admin@numm.gov.in", "admin123");
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem("operator@ongc.co.in")));

        String seniorToken = login("senior.reviewer@numm.gov.in", "reviewer123");
        mvc.perform(get("/api/mappings/audit").header("Authorization", bearer(seniorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("System Administrator"))
                .andExpect(jsonPath("$[0].rowHash").value("A".repeat(64)));
    }

    private User saveUser(String name, String email, String password, String role, Cpse cpse) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setCpse(cpse);
        user.setActive(true);
        return userRepository.save(user);
    }

    private String login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
