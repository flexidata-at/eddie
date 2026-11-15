// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.aiida.services;

import energy.eddie.regionconnector.aiida.mqtt.events.MqttConnectedEvent;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MqttEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttEventListener.class);
    private final AiidaPermissionService permissionService;

    private final MqttAsyncClient mqttClient;
    private final ApplicationContext applicationContext;
    private volatile boolean contextReady;

    public MqttEventListener(
            AiidaPermissionService permissionService,
            MqttAsyncClient mqttClient,
            ApplicationContext applicationContext
    ) {
        this.permissionService = permissionService;
        this.mqttClient = mqttClient;
        this.applicationContext = applicationContext;
    }

    @EventListener
    protected void onContextRefreshed(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != applicationContext) {
            return;
        }
        contextReady = true;
        // The asynchronous connection may complete before event listeners are registered.
        // Check the actual connection after all beans (including the message callback) exist.
        restoreSubscriptionsIfConnected();
    }

    @EventListener
    protected void onMqttConnectedEvent(MqttConnectedEvent event) {
        LOGGER.trace("Received MqttConnectedEvent: {}", event);
        restoreSubscriptionsIfConnected();
    }

    private void restoreSubscriptionsIfConnected() {
        if (contextReady && mqttClient.isConnected()) {
            permissionService.subscribeToAllActivePermissionTopics();
        }
    }
}
