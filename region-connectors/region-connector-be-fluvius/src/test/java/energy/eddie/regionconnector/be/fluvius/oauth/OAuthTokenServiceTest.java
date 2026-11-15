// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.be.fluvius.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import energy.eddie.regionconnector.be.fluvius.config.FluviusOAuthConfiguration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;

import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static energy.eddie.regionconnector.be.fluvius.config.FluviusOAuthConfiguration.AuthenticationMethod.CERTIFICATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class OAuthTokenServiceTest {
    private final MockWebServer server = new MockWebServer();
    private final DefaultSslBundleRegistry bundles = new DefaultSslBundleRegistry();
    private FluviusOAuthConfiguration config;

    @BeforeEach
    void setUp() throws Exception {
        server.start();
        config = new FluviusOAuthConfiguration(tokenUrl(), "client-id", "client-secret", "tenant-id", "api://test/.default");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void clientSecretRequestUsesBasicAuthenticationAndCachesOpaqueToken() throws Exception {
        server.enqueue(tokenResponse("opaque-access-token", 3600));
        var service = serviceForLocalServer();

        assertEquals("opaque-access-token", service.accessToken());
        assertEquals("opaque-access-token", service.accessToken());
        assertEquals(1, server.getRequestCount());
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=", request.getHeader("Authorization"));
        var body = request.getBody().readUtf8();
        assertTrue(body.contains("grant_type=client_credentials"));
        assertTrue(body.contains("scope=api%3A%2F%2Ftest%2F.default"));
        assertFalse(body.contains("client_assertion"));
    }

    @Test
    void tokenExpiringWithinOneMinuteIsRenewed() throws Exception {
        server.enqueue(tokenResponse("old-token", 30));
        server.enqueue(tokenResponse("new-token", 3600));
        var service = serviceForLocalServer();

        assertEquals("old-token", service.accessToken());
        assertEquals("new-token", service.accessToken());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void failedTokenRequestIsNotCached() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).addHeader("Content-Type", "application/json")
                                       .setBody("{\"error\":\"invalid_client\"}"));
        server.enqueue(tokenResponse("recovered-token", 3600));
        var service = serviceForLocalServer();

        assertThrows(OAuthException.class, service::accessToken);
        assertEquals("recovered-token", service.accessToken());
    }

    @Test
    void certificateRequestContainsSignedAssertionAndNoSecret() throws Exception {
        var fixture = new CertificateTestFixture();
        bundles.registerBundle("fluvius", fixture.bundle());
        config = new FluviusOAuthConfiguration(tokenUrl(), "live-client", null,
                                               "tenant-id", "api://test/.default", CERTIFICATE, "fluvius");
        var service = serviceForLocalServer();
        server.enqueue(tokenResponse("live-token", 3600));

        assertEquals("live-token", service.accessToken());
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertNull(request.getHeader("Authorization"));
        var parameters = com.nimbusds.oauth2.sdk.util.URLUtils.parseParameters(request.getBody().readUtf8());
        assertEquals("live-client", parameters.get("client_id").getFirst());
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", parameters.get("client_assertion_type").getFirst());
        assertFalse(parameters.containsKey("client_secret"));
        var assertion = SignedJWT.parse(parameters.get("client_assertion").getFirst());
        assertTrue(assertion.verify(new RSASSAVerifier((RSAPublicKey) fixture.certificate().getPublicKey())));
        assertEquals(JWSAlgorithm.PS256, assertion.getHeader().getAlgorithm());
        assertEquals(Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(fixture.certificate().getEncoded())),
                     assertion.getHeader().getX509CertSHA256Thumbprint());
        var claims = assertion.getJWTClaimsSet();
        assertEquals("live-client", claims.getIssuer());
        assertEquals("live-client", claims.getSubject());
        assertEquals(config.tokenUrl(), claims.getAudience().getFirst());
        assertNotNull(claims.getJWTID());
        assertNotNull(claims.getIssueTime());
        assertNotNull(claims.getNotBeforeTime());
        assertTrue(claims.getExpirationTime().toInstant().isAfter(Instant.now()));
        assertEquals(300, claims.getExpirationTime().toInstant().getEpochSecond() - claims.getIssueTime().toInstant().getEpochSecond());
    }

    @Test
    void certificateRenewalCreatesFreshAssertion() throws Exception {
        bundles.registerBundle("fluvius", new CertificateTestFixture().bundle());
        config = new FluviusOAuthConfiguration(tokenUrl(), "live-client", null,
                                               "tenant-id", "scope", CERTIFICATE, "fluvius");
        server.enqueue(tokenResponse("first", 0));
        server.enqueue(tokenResponse("second", 3600));
        var service = serviceForLocalServer();

        service.accessToken();
        service.accessToken();
        var first = server.takeRequest(1, TimeUnit.SECONDS);
        var second = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.getBody().readUtf8(), second.getBody().readUtf8());
    }

    @Test
    void missingCertificateBundleFailsAtStartup() {
        var certificateConfig = new FluviusOAuthConfiguration(config.tokenUrl(), "live-client", null,
                                                              "tenant", "scope", CERTIFICATE, "missing");
        assertThrows(org.springframework.boot.ssl.NoSuchSslBundleException.class,
                     () -> new OAuthTokenService(certificateConfig, bundles));
    }

    @Test
    void certificateSupportsExistingRs256Registration() throws Exception {
        var fixture = new CertificateTestFixture();
        bundles.registerBundle("fluvius", fixture.bundle());
        config = new FluviusOAuthConfiguration(tokenUrl(), "live-client", null, "tenant-id", "scope",
                CERTIFICATE, "fluvius", FluviusOAuthConfiguration.AssertionAlgorithm.RS256);
        var request = new OAuthTokenService(config, bundles).buildRequest();
        var parameters = com.nimbusds.oauth2.sdk.util.URLUtils.parseParameters(request.getBody());
        var assertion = SignedJWT.parse(parameters.get("client_assertion").getFirst());
        assertTrue(assertion.verify(new RSASSAVerifier((RSAPublicKey) fixture.certificate().getPublicKey())));
        assertEquals(JWSAlgorithm.RS256, assertion.getHeader().getAlgorithm());
        assertEquals(Base64URL.encode(MessageDigest.getInstance("SHA-1").digest(fixture.certificate().getEncoded())),
                assertion.getHeader().getX509CertThumbprint());
        assertNull(assertion.getHeader().getX509CertSHA256Thumbprint());
    }

    @Test
    void requestUsesBoundedTimeoutsAndNeverFollowsRedirects() throws Exception {
        var request = new OAuthTokenService(config, bundles).buildRequest();
        assertEquals(10_000, request.getConnectTimeout());
        assertEquals(20_000, request.getReadTimeout());
        assertFalse(request.getFollowRedirects());
    }

    @Test
    void tokenEndpointRejectsHttpUserInfoAndFragments() {
        for (var endpoint : java.util.List.of("http://localhost/token", "https://user@example.com/token", "https://example.com/token#fragment")) {
            var unsafe = new FluviusOAuthConfiguration(endpoint, "client", "secret", "tenant", "scope");
            var service = new OAuthTokenService(unsafe, bundles);
            assertThrows(OAuthException.class, service::buildRequest);
        }
    }

    @Test
    void remoteErrorCannotExposeCredentials() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\",\"error_description\":\"sensitive-provider-response\"}"));
        var service = serviceForLocalServer();
        var error = assertThrows(OAuthException.class, service::accessToken);
        assertEquals("Fluvius OAuth token request rejected", error.getMessage());
        assertFalse(error.toString().contains("sensitive-provider-response"));
    }

    @Test
    void certificateRefreshResolvesCurrentManagedBundle() throws Exception {
        var fixture = new CertificateTestFixture();
        var managedBundles = mock(org.springframework.boot.ssl.SslBundles.class);
        when(managedBundles.getBundle("fluvius")).thenReturn(fixture.bundle());
        var certificateConfig = new FluviusOAuthConfiguration(tokenUrl(), "client", null, "tenant", "scope", CERTIFICATE, "fluvius");
        var service = new OAuthTokenService(certificateConfig, managedBundles);
        service.buildRequest();
        service.buildRequest();
        verify(managedBundles, times(3)).getBundle("fluvius");
    }

    private String tokenUrl() {
        return server.url("/token").toString().replace("http:", "https:");
    }

    private OAuthTokenService serviceForLocalServer() throws Exception {
        var service = spy(new OAuthTokenService(config, bundles));
        // Keep production HTTPS validation while exercising the HTTP mock server as a test transport.
        doAnswer(invocation -> {
            HTTPRequest original = invocation.getArgument(0);
            var local = new HTTPRequest(original.getMethod(), server.url("/token").url());
            original.getHeaderMap().forEach((name, values) -> local.setHeader(name, values.toArray(String[]::new)));
            local.setBody(original.getBody());
            local.setConnectTimeout(original.getConnectTimeout());
            local.setReadTimeout(original.getReadTimeout());
            local.setFollowRedirects(original.getFollowRedirects());
            return TokenResponse.parse(local.send());
        }).when(service).requestToken(any(HTTPRequest.class));
        return service;
    }

    private static MockResponse tokenResponse(String token, int expiresIn) {
        return new MockResponse().addHeader("Content-Type", "application/json")
                                 .setBody("{\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ",\"access_token\":\"" + token + "\"}");
    }
}
