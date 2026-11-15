// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.aiida.services;

import energy.eddie.regionconnector.aiida.mqtt.callback.MqttMessageCallback;
import energy.eddie.regionconnector.aiida.mqtt.events.MqttConnectedEvent;
import energy.eddie.regionconnector.aiida.mqtt.message.processor.AiidaMessageProcessorRegistry;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.mockito.Mockito.*;

class MqttSubscriptionRecoveryTest {
    private final AiidaPermissionService permissions = mock(AiidaPermissionService.class);
    private final MqttAsyncClient client = mock(MqttAsyncClient.class);

    @Test
    void restoresSubscriptionsWhenMqttConnectedBeforeListenersWereRegistered() {
        when(client.isConnected()).thenReturn(true);
        try (var context = context()) {
            verify(permissions).subscribeToAllActivePermissionTopics();
        }
    }

    @Test
    void waitsForConnectionWhenContextStartsDisconnected() {
        try (var context = context()) {
            verifyNoInteractions(permissions);
            when(client.isConnected()).thenReturn(true);
            context.publishEvent(new MqttConnectedEvent("tcp://broker:1883"));
            verify(permissions).subscribeToAllActivePermissionTopics();
        }
    }

    @Test
    void automaticReconnectRestoresSubscriptionsThroughTheSpringManagedCallback() {
        when(client.isConnected()).thenReturn(true);
        try (var context = context()) {
            verify(permissions).subscribeToAllActivePermissionTopics();
            clearInvocations(permissions);
            context.getBean(MqttMessageCallback.class).connectComplete(true, "tcp://broker:1883");
            verify(permissions).subscribeToAllActivePermissionTopics();
        }
    }

    @Test
    void ignoresOtherApplicationContexts() {
        try (var context = context(); var otherContext = new AnnotationConfigApplicationContext()) {
            when(client.isConnected()).thenReturn(true);
            context.publishEvent(new ContextRefreshedEvent(otherContext));
            verifyNoInteractions(permissions);
        }
    }

    @Test
    void initialConnectionCallbackDoesNotDuplicateTheConnectActionEvent() {
        try (var context = context()) {
            context.getBean(MqttMessageCallback.class).connectComplete(false, "tcp://broker:1883");
            verifyNoInteractions(permissions);
        }
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(AiidaPermissionService.class, () -> permissions);
        context.registerBean(MqttAsyncClient.class, () -> client);
        context.registerBean(MqttEventListener.class);
        context.registerBean(MqttMessageCallback.class,
                () -> new MqttMessageCallback(mock(AiidaMessageProcessorRegistry.class)));
        context.refresh();
        return context;
    }
}
