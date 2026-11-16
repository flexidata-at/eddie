// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.core.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.core.ManagementApiConfig;
import energy.eddie.core.services.agnostic.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EnergySharingMeasurementImportControllerTest {
  private static final String PATH =
      "/management/energy-sharing/at-eda/communities/community/measurement-documents/import";
  private static final String TOKEN = "synthetic-local-import-test-credential-only";
  private static final String HASH = "a".repeat(64);
  private final EnergySharingRouter router = mock(EnergySharingRouter.class);

  private MockMvc mvc(boolean enabled) throws Exception {
    String digest =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
    var scope =
        new EnergySharingImportAuthorization.Scope("at-eda", "operator", "connection", "community");
    var gate =
        new EnergySharingImportAuthorization(
            new EnergySharingImportAuthorization.Settings(
                enabled, digest, Instant.now().plusSeconds(600), List.of(scope)));
    return MockMvcBuilders.standaloneSetup(
            new EnergySharingMeasurementImportController(router, gate))
        .addPlaceholderValue("eddie.management.server.urlprefix", "management")
        .build();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
    return post(PATH)
        .param("operatorId", "operator")
        .param("connectionId", "connection")
        .contentType("application/xml")
        .header("Authorization", "Bearer " + TOKEN)
        .header("X-Archive-Id", "ponton:123")
        .header("X-Archive-Received-At", "2026-11-15T12:00:00Z")
        .header("X-Archive-Payload-Sha256", HASH)
        .content("<native/>");
  }

  @Test
  void defaultsToValidationAndRequiresExplicitCommitFlag() throws Exception {
    mvc(true)
        .perform(request())
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(router)
        .importMeasurement(
            eq("at-eda"),
            eq("community"),
            eq("operator"),
            eq("connection"),
            argThat(EnergySharingMeasurementImport::dryRun));
    mvc(true).perform(request().param("dryRun", "false")).andExpect(status().isOk());
    verify(router)
        .importMeasurement(
            eq("at-eda"),
            eq("community"),
            eq("operator"),
            eq("connection"),
            argThat(value -> !value.dryRun()));
  }

  @Test
  void disabledGateForeignScopeAndUnknownTokenCannotReachIngest() throws Exception {
    mvc(false).perform(request()).andExpect(status().isForbidden());
    mvc(true)
        .perform(request().param("communityId", "foreign").param("connectionId", "foreign"))
        .andExpect(status().isForbidden());
    mvc(true)
        .perform(
            post(PATH)
                .param("operatorId", "operator")
                .param("connectionId", "connection")
                .contentType("application/xml")
                .content("<native/>"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(router);
  }

  @Test
  void invalidMetadataAndOversizeInputCannotReachIngest() throws Exception {
    mvc(true)
        .perform(
            post(PATH)
                .param("operatorId", "operator")
                .param("connectionId", "connection")
                .contentType("application/xml")
                .header("Authorization", "Bearer " + TOKEN)
                .content("<native/>"))
        .andExpect(status().isBadRequest());
    mvc(true)
        .perform(request().content(new byte[EnergySharingMeasurementImport.MAX_BYTES + 1]))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(router);
  }

  @Test
  void publicPortRejectsImportEvenWithCredential() throws Exception {
    var http = new MockHttpServletRequest();
    http.setRequestURI("/eddie" + PATH);
    http.setContextPath("/eddie");
    http.setLocalPort(8080);
    http.addHeader("Authorization", "Bearer " + TOKEN);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    new ManagementApiConfig(9090, "management")
        .trustedEndpointsFilter()
        .getFilter()
        .doFilter(http, response, chain);
    assertEquals(404, response.getStatus());
    assertNull(chain.getRequest());
  }
}
