// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.web;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.core.ManagementApiConfig;
import energy.eddie.core.services.agnostic.EnergySharingRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EnergySharingManagementControllerTest {
    private final EnergySharingRouter router = mock(EnergySharingRouter.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new EnergySharingManagementController(router))
                .addPlaceholderValue("eddie.management.server.urlprefix", "management").build();
    }

    @Test
    void serializesSubmittedAsAcceptedHttpButNotActiveMembership() throws Exception {
        var now = Instant.parse("2026-11-15T10:00:00Z");
        when(router.submit(eq("at-eda"), any())).thenReturn(new EnergySharingOperation("req", "connection", "operator",
                "community", "JOIN", "SUBMITTED", now, now, "native-message", null, null, new JsonMapper().createObjectNode()));
        mvc.perform(post("/management/energy-sharing/at-eda/operations").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req","connectionId":"connection","operatorId":"operator","communityId":"community",
                 "operation":"JOIN","profileVersion":"1","parameters":{"participationFactor":25,"allocationFactor":12.3456}}
                """))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        verify(router).submit(eq("at-eda"), argThat(request -> request.parameters().path("allocationFactor").decimalValue()
                .compareTo(new java.math.BigDecimal("12.3456")) == 0));
    }

    @Test
    void rejectsMalformedScopeAndMapsNationalValidationErrors() throws Exception {
        mvc.perform(post("/management/energy-sharing/at-eda/operations").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req","connectionId":"","operatorId":"operator","communityId":"community",
                 "operation":"JOIN","profileVersion":"1","parameters":{}}
                """))
                .andExpect(status().isBadRequest());
        when(router.capabilities("at-eda", "operator", "connection")).thenThrow(
                new EnergySharingException(EnergySharingException.Code.FORBIDDEN, "Operator is not assigned"));
        mvc.perform(get("/management/energy-sharing/at-eda/capabilities").param("operatorId", "operator").param("connectionId", "connection"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void newManagementRoutesAreUnavailableOnThePublicPort() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/eddie/management/energy-sharing/at-eda/operations");
        request.setContextPath("/eddie");
        request.setLocalPort(8080);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ManagementApiConfig(9090, "management").trustedEndpointsFilter().getFilter().doFilter(request, response, chain);
        assertEquals(404, response.getStatus());
        assertNull(chain.getRequest());
    }
    @Test
    void encodedManagementPrefixUsesTheContainersDecodedMapping() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/eddie/%6danagement/energy-sharing/at-eda/operations");
        request.setContextPath("/eddie");
        request.setServletPath("/management/energy-sharing/at-eda/operations");
        request.setLocalPort(8080);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ManagementApiConfig(9090, "management").trustedEndpointsFilter().getFilter().doFilter(request, response, chain);
        assertEquals(404, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void encodedRegionalManagementPathIsAlsoBlocked() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/eddie/region-connectors/at-eda/%6danagement/cesu-join-request");
        request.setContextPath("/eddie");
        request.setServletPath("/region-connectors/at-eda");
        request.setPathInfo("/management/cesu-join-request");
        request.setLocalPort(8080);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ManagementApiConfig(9090, "management").trustedEndpointsFilter().getFilter().doFilter(request, response, chain);
        assertEquals(404, response.getStatus());
        assertNull(chain.getRequest());
    }

}
