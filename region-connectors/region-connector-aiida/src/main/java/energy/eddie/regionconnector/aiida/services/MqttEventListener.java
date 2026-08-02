// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.aiida.services;

import energy.eddie.regionconnector.aiida.mqtt.events.MqttConnectedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MqttEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttEventListener.class);
    private final AiidaPermissionService permissionService;

    public MqttEventListener(AiidaPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @EventListener
    protected void onMqttConnectedEvent(MqttConnectedEvent event) {
        LOGGER.trace("Received MqttConnectedEvent: {}", event);
        permissionService.subscribeToAllActivePermissionTopics();
    }

    @EventListener(ApplicationReadyEvent.class)
    protected void onApplicationReady() {
        LOGGER.trace("Application ready, subscribing to active AIIDA MQTT topics");
        permissionService.subscribeToAllActivePermissionTopics();
    }
}
