/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.mqtt.strategy;

import static org.apache.activemq.transport.mqtt.MQTTProtocolSupport.convertActiveMQToMQTT;
import static org.apache.activemq.transport.mqtt.MQTTProtocolSupport.convertMQTTToActiveMQ;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.DestinationInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.store.PersistenceAdapterSupport;
import org.apache.activemq.transport.mqtt.MQTTProtocolConverter;
import org.apache.activemq.transport.mqtt.MQTTProtocolException;
import org.apache.activemq.transport.mqtt.MQTTSubscription;
import org.apache.activemq.transport.mqtt.ResponseHandler;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.codec.CONNECT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscription strategy that converts all MQTT subscribes that would be durable to
 * Virtual Topic Queue subscriptions.  Also maps all publish requests to be prefixed
 * with the VirtualTopic. prefix unless already present.
 */
public class MQTTVirtualTopicSubscriptionStrategy extends AbstractMQTTSubscriptionStrategy {

    private static final String VIRTUALTOPIC_PREFIX = "VirtualTopic.";
    private static final String VIRTUALTOPIC_CONSUMER_PREFIX = "Consumer.";

    private static final Logger LOG = LoggerFactory.getLogger(MQTTVirtualTopicSubscriptionStrategy.class);

    private final Set<ActiveMQQueue> restoredQueues = Collections.synchronizedSet(new HashSet<ActiveMQQueue>());

    @Override
    public void onConnect(CONNECT connect) throws MQTTProtocolException {
        List<ActiveMQQueue> queues;
        try {
            queues = PersistenceAdapterSupport.listQueues(brokerService.getPersistenceAdapter(), new PersistenceAdapterSupport.DestinationMatcher() {

                @Override
                public boolean matches(ActiveMQDestination destination) {
                    if (destination.getPhysicalName().startsWith("Consumer." + protocol.getClientId())) {
                        LOG.debug("Recovered client sub: {}", destination.getPhysicalName());
                        return true;
                    }
                    return false;
                }
            });
        } catch (IOException e) {
            throw new MQTTProtocolException("Error restoring durable subscriptions", true, e);
        }

        if (connect.cleanSession()) {
            deleteDurableQueues(queues);
        } else {
            restoreDurableQueue(queues);
        }
    }

    @Override
    public byte onSubscribe(String topicName, QoS requestedQoS) throws MQTTProtocolException {
        ActiveMQDestination destination = null;
        if (!protocol.isCleanSession() && protocol.getClientId() != null && requestedQoS.ordinal() >= QoS.AT_LEAST_ONCE.ordinal()) {
            String converted = VIRTUALTOPIC_CONSUMER_PREFIX + protocol.getClientId() + ":" + requestedQoS + "." +
                               VIRTUALTOPIC_PREFIX + convertMQTTToActiveMQ(topicName);
            destination = new ActiveMQQueue(converted);
        } else {
            String converted = convertMQTTToActiveMQ(topicName);
            if (!converted.startsWith(VIRTUALTOPIC_PREFIX)) {
                converted = VIRTUALTOPIC_PREFIX + convertMQTTToActiveMQ(topicName);
            }
            destination = new ActiveMQTopic(converted);
        }

        ConsumerInfo consumerInfo = new ConsumerInfo(protocol.getNextConsumerId());
        consumerInfo.setDestination(destination);
        consumerInfo.setPrefetchSize(protocol.getActiveMQSubscriptionPrefetch());
        consumerInfo.setRetroactive(true);
        consumerInfo.setDispatchAsync(true);

        return protocol.doSubscribe(consumerInfo, topicName, requestedQoS);
    }

    @Override
    public void onReSubscribe(MQTTSubscription mqttSubscription) throws MQTTProtocolException {

        ActiveMQDestination destination = mqttSubscription.getDestination();

        // check whether the Topic has been recovered in restoreDurableSubs
        // mark subscription available for recovery for duplicate subscription
        if (restoredQueues.remove(destination)) {
            return;
        }

        if (mqttSubscription.getDestination().isTopic()) {
            super.onReSubscribe(mqttSubscription);
        } else {
            protocol.doUnSubscribe(mqttSubscription);
            ConsumerInfo consumerInfo = mqttSubscription.getConsumerInfo();
            consumerInfo.setConsumerId(protocol.getNextConsumerId());
            protocol.doSubscribe(consumerInfo, mqttSubscription.getTopicName(), mqttSubscription.getQoS());
        }
    }

    @Override
    public void onUnSubscribe(MQTTSubscription subscription) throws MQTTProtocolException {
        if (subscription.getDestination().isQueue()) {
            DestinationInfo remove = new DestinationInfo();
            remove.setConnectionId(protocol.getConnectionId());
            remove.setDestination(subscription.getDestination());
            remove.setOperationType(DestinationInfo.REMOVE_OPERATION_TYPE);

            protocol.sendToActiveMQ(remove, new ResponseHandler() {
                @Override
                public void onResponse(MQTTProtocolConverter converter, Response response) throws IOException {
                    // ignore failures..
                }
            });
        }
    }

    @Override
    public ActiveMQDestination onSend(String topicName) {
        if (!topicName.startsWith(VIRTUALTOPIC_PREFIX)) {
            return new ActiveMQTopic(VIRTUALTOPIC_PREFIX + topicName);
        } else {
            return new ActiveMQTopic(topicName);
        }
    }

    @Override
    public String onSend(ActiveMQDestination destination) {
        String amqTopicName = destination.getPhysicalName();
        if (amqTopicName.startsWith(VIRTUALTOPIC_PREFIX)) {
            amqTopicName = amqTopicName.substring(VIRTUALTOPIC_PREFIX.length());
        }
        return amqTopicName;
    }

    @Override
    public boolean isControlTopic(ActiveMQDestination destination) {
        String destinationName = destination.getPhysicalName();
        if (destinationName.startsWith("$") || destinationName.startsWith(VIRTUALTOPIC_PREFIX + "$")) {
            return true;
        }
        return false;
    }

    private void deleteDurableQueues(List<ActiveMQQueue> queues) {
        try {
            for (ActiveMQQueue queue : queues) {
                DestinationInfo removeAction = new DestinationInfo();
                removeAction.setConnectionId(protocol.getConnectionId());
                removeAction.setDestination(queue);
                removeAction.setOperationType(DestinationInfo.REMOVE_OPERATION_TYPE);

                protocol.sendToActiveMQ(removeAction, new ResponseHandler() {
                    @Override
                    public void onResponse(MQTTProtocolConverter converter, Response response) throws IOException {
                        // ignore failures..
                    }
                });
            }
        } catch (Throwable e) {
            LOG.warn("Could not delete the MQTT durable subs.", e);
        }
    }

    private void restoreDurableQueue(List<ActiveMQQueue> queues) {
        try {
            for (ActiveMQQueue queue : queues) {
                String name = queue.getPhysicalName().substring(VIRTUALTOPIC_CONSUMER_PREFIX.length());
                StringTokenizer tokenizer = new StringTokenizer(name);
                tokenizer.nextToken(":.");
                String qosString = tokenizer.nextToken();
                tokenizer.nextToken();
                String topicName = convertActiveMQToMQTT(tokenizer.nextToken("").substring(1));
                QoS qoS = QoS.valueOf(qosString);
                LOG.trace("Restoring subscription: {}:{}", topicName, qoS);

                ConsumerInfo consumerInfo = new ConsumerInfo(protocol.getNextConsumerId());
                consumerInfo.setDestination(queue);
                consumerInfo.setPrefetchSize(protocol.getActiveMQSubscriptionPrefetch());
                consumerInfo.setRetroactive(true);
                consumerInfo.setDispatchAsync(true);

                protocol.doSubscribe(consumerInfo, topicName, qoS);

                // mark this durable subscription as restored by Broker
                restoredQueues.add(queue);
            }
        } catch (IOException e) {
            LOG.warn("Could not restore the MQTT durable subs.", e);
        }
    }
}