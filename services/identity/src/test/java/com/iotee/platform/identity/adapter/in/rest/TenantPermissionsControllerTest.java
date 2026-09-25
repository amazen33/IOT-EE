package com.iotee.platform.identity.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-context REST test: real component scan, real composition root
 * ({@code config.IdentityServiceConfig}), real in-memory outbound
 * adapter. The gRPC server binds an ephemeral port so this test never
 * collides with a running service or another test.
 */
@SpringBootTest(properties = "iotee.identity.grpc.port=0")
@AutoConfigureMockMvc
class TenantPermissionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";

    @Test
    void adminSubjectReceivesBothPermissions() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.tenantId").value(SYNTHETIC_TENANT))
                .andExpect(jsonPath("$.subjectId").value("synthetic-subject-admin"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions[0]").value("TENANT_MANAGE"))
                .andExpect(jsonPath("$.permissions[1]").value("TENANT_READ"));
    }

    @Test
    void viewerSubjectReceivesOnlyReadPermission() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("TENANT_READ"));
    }

    @Test
    void unassignedSubjectReceivesNoPermissions() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-unassigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }

    @Test
    void nonSyntheticTenantIdIsRejected() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", "tenant-prod-4471")
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectedRequestBodyNeverEchoesInternalValidationDetail() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", "tenant-prod-4471")
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.detail").value("request could not be validated"));
    }

    @Test
    void blankSubjectIdIsRejected() throws Exception {
        // Step C1: blank subject is invalid on every transport (see
        // GetTenantPermissionsService's Javadoc).
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyResponseCarriesTheCorrelationIdResponseHeader() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("X-Correlation-ID"));
    }
}
