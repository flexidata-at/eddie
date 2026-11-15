// SPDX-FileCopyrightText: 2024 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.be.fluvius.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import energy.eddie.regionconnector.be.fluvius.config.FluviusOAuthConfiguration;
import jakarta.annotation.Nullable;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OAuthTokenService {
    private final FluviusOAuthConfiguration config;
    private final ClientAuthenticationFactory authenticationFactory;
    @Nullable
    private String accessToken = null;
    private Instant expiresAt = Instant.MIN;

    public OAuthTokenService(FluviusOAuthConfiguration config, SslBundles sslBundles) {
        this.config = config;
        switch (config.authenticationMethod()) {
            case CLIENT_SECRET -> {
                var authentication = new ClientSecretBasic(new ClientID(config.clientId()),
                                                           new Secret(Objects.requireNonNull(config.clientSecret())));
                this.authenticationFactory = () -> authentication;
            }
            case CERTIFICATE -> {
                // Validate at startup, then resolve the managed bundle again on each token refresh.
                new CertificateClientAuthentication(config.clientId(), URI.create(config.tokenUrl()),
                                                     sslBundles.getBundle(config.sslBundle()), config.assertionAlgorithm());
                this.authenticationFactory = () -> new CertificateClientAuthentication(
                        config.clientId(), URI.create(config.tokenUrl()),
                        sslBundles.getBundle(config.sslBundle()), config.assertionAlgorithm()).create();
            }
            default -> throw new IllegalArgumentException("Unsupported Fluvius OAuth authentication method");
        }
    }

    public synchronized String accessToken() throws URISyntaxException, IOException, ParseException, OAuthException {
        var now = Instant.now();
        if (accessToken != null && expiresAt.isAfter(now.plusSeconds(60))) {
            return accessToken;
        }
        var response = requestToken(buildRequest());
        if (!response.indicatesSuccess()) {
            throw new OAuthException("Fluvius OAuth token request rejected");
        }
        var successResponse = response.toSuccessResponse();
        if (successResponse.getTokens() == null || successResponse.getTokens().getAccessToken() == null) {
            throw new OAuthException("Fluvius OAuth response contained no access token");
        }
        accessToken = successResponse.getTokens().getAccessToken().getValue();
        expiresAt = now.plusSeconds(successResponse.getTokens().getAccessToken().getLifetime());
        return accessToken;
    }

    TokenResponse requestToken(HTTPRequest request) throws IOException, ParseException {
        return TokenResponse.parse(request.send());
    }

    HTTPRequest buildRequest() throws URISyntaxException, OAuthException {
        var tokenEndpoint = new URI(config.tokenUrl());
        if (!"https".equalsIgnoreCase(tokenEndpoint.getScheme()) || tokenEndpoint.getHost() == null
                || tokenEndpoint.getUserInfo() != null || tokenEndpoint.getFragment() != null) {
            throw new OAuthException("Fluvius OAuth requires a valid HTTPS token endpoint");
        }
        final ClientAuthentication authentication;
        try {
            authentication = authenticationFactory.create();
        } catch (GeneralSecurityException | JOSEException | IllegalArgumentException exception) {
            throw new OAuthException("Cannot sign Fluvius OAuth client assertion");
        }
        // Microsoft requires client_id in addition to the signed assertion.
        var request = new TokenRequest(tokenEndpoint, authentication, new ClientCredentialsGrant(), new Scope(config.scope()), null,
                                       Map.of("client_id", List.of(config.clientId()))).toHTTPRequest();
        request.setConnectTimeout(10_000);
        request.setReadTimeout(20_000);
        request.setFollowRedirects(false);
        return request;
    }

    @FunctionalInterface
    private interface ClientAuthenticationFactory {
        ClientAuthentication create() throws GeneralSecurityException, JOSEException;
    }
}
