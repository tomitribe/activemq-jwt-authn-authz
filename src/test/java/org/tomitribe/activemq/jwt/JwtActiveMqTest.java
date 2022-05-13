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
package org.tomitribe.activemq.jwt;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import static org.junit.Assert.fail;

public class JwtActiveMqTest {
    @Rule
    public EmbeddedActiveMQBroker customizedBroker = new EmbeddedActiveMQBroker() {
        @Override
        protected void configure() {
            try {
                getBrokerService().addConnector("tcp://0.0.0.0:61616?maximumConnections=10");
                getBrokerService().setPlugins(new BrokerPlugin[]{new JwtAuthenticationPlugin()});

            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

    @Test
    public void noToken() throws Exception {
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());

        final int numberOfMessages = 1;
        final int messageSize = 10;
        final String destinationName = "NO_TOKEN";

        try {
            send(cf, destinationName, numberOfMessages, messageSize);
        } catch (JMSException e) {
            fail("Should not have failed because token is missing. See JwtAuthenticationBroker.");
        }

        // we should have 1 message actually in the queue
        Assert.assertEquals(1, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void badFormattedToken() throws Exception {
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName("bla");
        cf.setPassword("");

        final int numberOfMessages = 10;
        final int messageSize = 0;
        final String destinationName = "BAD_TOKEN";

        try {
            send(cf, destinationName, numberOfMessages, messageSize);
            fail("Should have failed because token is invalid.");
        } catch (JMSException e) {
            // ok
        }

        // we should have zero messages actually in the queue
        Assert.assertEquals(0, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void valid() throws Exception {
        final String token = TokenUtils.token(false);
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName(token);
        cf.setPassword("");

        final int numberOfMessages = 10;
        final int messageSize = 10;
        final String destinationName = "QUEUE";

        try {
            send(cf, destinationName, numberOfMessages, messageSize);
        } catch (JMSException e) {
            fail("Should not have failed because token is valid.");
        }

        // we should have zero messages actually in the queue
        Assert.assertEquals(10, customizedBroker.getMessageCount(destinationName));
    }

    private void send(final ActiveMQConnectionFactory cf, final String destinationName,
                final int numberOfMessages, final int messageSize) throws JMSException {

        try (final Connection conn = cf.createConnection()) {
            conn.start();
            try (final Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                final Queue queue = session.createQueue(destinationName);
                try (final MessageProducer producer = session.createProducer(queue)) {
                    for (int i = 0; i < numberOfMessages; i++) {
                        final String messageText;
                        if (messageSize < 1) {
                            messageText = "Test message: " + i;
                        } else {
                            messageText = RandomStringUtils.randomAlphanumeric(messageSize);
                        }

                        final TextMessage message = session.createTextMessage(messageText);
                        message.setIntProperty("message_nb", i);
                        producer.send(message);
                    }
                }
            }
        }
    }

}
