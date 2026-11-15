// SPDX-FileCopyrightText: 2-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.aiida.mqtt.callback;

import energy.eddie.regionconnector.aiida.exceptions.AiidaMessageProcessorRegistryException;
import energy.eddie.regionconnector.aiida.exceptions.PermissionInvalidException;
import energy.eddie.regionconnector.aiida.mqtt.events.MqttConnectedEvent;
import energy.eddie.regionconnector.aiida.mqtt.message.processor.AiidaMessageProcessorRegistry;
import energy.eddie.regionconnector.shared.exceptions.PermissionNotFoundException;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

import java.io.IOException;

public class MqttMessageCallback implements MqttCallback, ApplicationEventPublisherAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttMessageCallback.class);
    private final AiidaMessageProcessorRegistry messageProcessorRegistry;
    @Nullable
    private ApplicationEventPublisher eventPublisher;

    public MqttMessageCallback(
            AiidaMessageProcessorRegistry messageProcessorRegistry
    ) {
        this.messageProcessorRegistry = messageProcessorRegistry;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        LOGGER.warn("Disconnected from MQTT broker {}", disconnectResponse);
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        LOGGER.error("Mqtt error occurred", exception);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            var messageProcessor = messageProcessorRegistry.processorFor(topic);
            messageProcessor.processMessage(message);
        } catch (IOException | PermissionNotFoundException | PermissionInvalidException exception) {
            LOGGER.error("Could not process MQTT message on topic {}", topic, exception);
        } catch (AiidaMessageProcessorRegistryException exception) {
            LOGGER.debug("Received MQTT message, but no processor is registered, ignoring message", exception);
        }
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        LOGGER.trace("Delivery complete for MqttToken {}", token);
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        LOGGER.info("Connected to MQTT broker {}, was because of reconnect: {}", serverURI, reconnect);
        // Paho automatic reconnect does not invoke the initial connect action listener.
        var publisher = eventPublisher;
        if (reconnect && publisher != null) {
            publisher.publishEvent(new MqttConnectedEvent(serverURI));
        }
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // Not needed, as no advanced authentication is required
    }
}
