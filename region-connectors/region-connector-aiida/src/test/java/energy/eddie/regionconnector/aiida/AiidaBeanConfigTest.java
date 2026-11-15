package energy.eddie.regionconnector.aiida;

import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.regionconnector.aiida.config.AiidaConfiguration;
import energy.eddie.regionconnector.aiida.permission.request.AiidaPermissionRequest;
import energy.eddie.regionconnector.aiida.permission.request.persistence.AiidaPermissionRequestViewRepository;
import energy.eddie.regionconnector.shared.event.sourcing.EventBusImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiidaBeanConfigTest {
    @Mock
    private AiidaConfiguration aiidaConfiguration;

    private AiidaBeanConfig aiidaBeanConfig;

    @BeforeEach
    void setUp() {
        aiidaBeanConfig = new AiidaBeanConfig();
    }

    @Test
    void connectionOptions_shouldCreateMqttConnectionOptions() {
        // Given
        var mqttUsername = "testUser";
        var mqttPassword = "testPassword";
        when(aiidaConfiguration.mqttUsername()).thenReturn(mqttUsername);
        when(aiidaConfiguration.mqttPassword()).thenReturn(mqttPassword);

        // When
        var connectionOptions = aiidaBeanConfig.connectionOptions(aiidaConfiguration);

        // Then
        assertEquals(mqttUsername, connectionOptions.getUserName());
        assertArrayEquals(mqttPassword.getBytes(StandardCharsets.UTF_8), connectionOptions.getPassword());
    }

    @Test
    void connectionOptions_shouldSkipPassword_whenEmpty(){
        // Given
        var mqttUsername = "testUser";
        var mqttPassword = "  ";

        when(aiidaConfiguration.mqttUsername()).thenReturn(mqttUsername);
        when(aiidaConfiguration.mqttPassword()).thenReturn(mqttPassword);

        // When
        var connectionOptions = aiidaBeanConfig.connectionOptions(aiidaConfiguration);

        // Then
        assertEquals(mqttUsername, connectionOptions.getUserName());
        assertNull(connectionOptions.getPassword());
    }

    @Test
    void connectionStatusMessageHandlerReplaysPersistedAiidaStatus() {
        var repository = mock(AiidaPermissionRequestViewRepository.class);
        var permission = mock(AiidaPermissionRequest.class);
        when(permission.permissionId()).thenReturn("permission");
        when(permission.connectionId()).thenReturn("connection");
        when(permission.dataNeedId()).thenReturn("need");
        when(permission.status()).thenReturn(PermissionProcessStatus.ACCEPTED);
        when(repository.findByStatusIn(EnumSet.allOf(PermissionProcessStatus.class)))
                .thenReturn(List.of(permission));

        try (var handler = aiidaBeanConfig.connectionStatusMessageHandler(new EventBusImpl(), repository)) {
            StepVerifier.create(handler.getConnectionStatusMessageStream())
                    .expectNextMatches(message -> message.permissionId().equals("permission")
                            && message.status() == PermissionProcessStatus.ACCEPTED)
                    .thenCancel()
                    .verify();
        }
    }
}
