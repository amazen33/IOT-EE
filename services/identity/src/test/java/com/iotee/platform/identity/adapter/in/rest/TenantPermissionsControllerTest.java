package com.iotee.platform.identity.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 *
 * <p>The real composition root wires a real
 * {@code adapter.out.jwt.NimbusTokenSignatureVerifier} pointed at a
 * placeholder, unreachable JWKS URI (there is no real identity provider
 * deployed yet), so every request here is necessarily unauthenticated --
 * this class can only test the 401 path end to end through Spring, never
 * a 200. The exhaustive authentication/authorization negatives, and the
 * one 200 path, are proven without Spring in
 * {@code application.GetTenantPermissionsServiceTest}; this class's job
 * is to prove Spring wiring reaches the SAME outcome the same way REST
 * always has: HTTP 400 for {@code InvalidQueryException}, and now 401 for
 * {@code AuthenticationFailedException}.
 */
@SpringBootTest(properties = "iotee.identity.grpc.port=0")
@AutoConfigureMockMvc
class TenantPermissionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SYNTHETIC_TENANT = "synthetic-tenant-acme-001";

    @Test
    void aRequestWithNoAuthorizationHeaderIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value(RestExceptionAdvice.AUTHENTICATION_ERROR_CODE))
                .andExpect(jsonPath("$.detail").value(RestExceptionAdvice.AUTHENTICATION_CLIENT_SAFE_DETAIL));
    }

    @Test
    void aSpoofedGatewayIdentityHeaderAloneNeverGrantsAuthority() throws Exception {
        // No Authorization header at all -- only a plausible gateway-style identity claim, of
        // exactly the kind ADR 0016 Decision 4 says must stay advisory and this controller never
        // reads (TenantPermissionsController has no @RequestHeader for any such header). If it
        // were consulted, this request would succeed as a system admin; instead it is rejected
        // exactly like any other unauthenticated request.
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer")
                        .header("X-Iotee-Subject-Id", "synthetic-subject-admin")
                        .header("X-Iotee-Subject-Tier", "PRODUCT_ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aNonBearerAuthorizationHeaderIsTreatedAsNoTokenAtAll() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anObviouslyForgedBearerTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer")
                        .header("Authorization", "Bearer this-was-never-issued-by-any-idp"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(RestExceptionAdvice.AUTHENTICATION_ERROR_CODE));
    }

    @Test
    void nonSyntheticTenantIdIsRejectedBeforeAuthenticationIsEvenAttempted() throws Exception {
        // No Authorization header, yet the response is 400 (not 401): tenantId/subjectId
        // validation runs before token verification (GetTenantPermissionsService's Javadoc).
        mockMvc.perform(get("/tenants/{tenantId}/permissions", "tenant-prod-4471")
                        .param("subjectId", "synthetic-subject-admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(RestExceptionAdvice.ERROR_CODE));
    }

    @Test
    void blankSubjectIdIsRejectedBeforeAuthenticationIsEvenAttempted() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyResponseCarriesTheCorrelationIdResponseHeaderEvenWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/tenants/{tenantId}/permissions", SYNTHETIC_TENANT)
                        .param("subjectId", "synthetic-subject-viewer"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-ID"));
    }
}
