// SPDX-FileCopyrightText: 2025-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.aiida.services;

import energy.eddie.regionconnector.aiida.mqtt.events.MqttConnectedEvent;
import energy.eddie.regionconnector.aiida.publisher.MqttEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {MqttEventListener.class, MqttEventPublisher.class})
class MqttEventListenerTest {
    @Autowired
    private MqttEventPublisher mqttEventPublisher;
    @MockitoBean
    private AiidaPermissionService aiidaPermissionService;
    @SuppressWarnings("unused")
    @MockitoSpyBean
    @InjectMocks
    private MqttEventListener mqttEventListener;
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        clearInvocations(aiidaPermissionService);
    }

    @Test
    void onMqttConnectedEvent_subscribeToAllActivePermissionTopics() {
        // Given
        var event = mock(MqttConnectedEvent.class);

        // When
        mqttEventPublisher.publishEvent(event);

        // Then
        verify(aiidaPermissionService).subscribeToAllActivePermissionTopics();
    }

    @Test
    void onApplicationReady_subscribeToAllActivePermissionTopics() {
        // When
        applicationContext.publishEvent(new ApplicationReadyEvent(
                mock(org.springframework.boot.SpringApplication.class),
                new String[0],
                applicationContext,
                java.time.Duration.ZERO));

        // Then
        verify(aiidaPermissionService).subscribeToAllActivePermissionTopics();
    }
}
