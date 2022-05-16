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
import org.apache.activemq.security.AuthorizationPlugin;
import org.apache.activemq.security.XBeanAuthorizationEntry;
import org.apache.activemq.security.XBeanAuthorizationMap;
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
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.fail;

public class JwtActiveMqTest {
    @Rule
    public EmbeddedActiveMQBroker customizedBroker = new EmbeddedActiveMQBroker() {
        @Override
        protected void configure() {
            try {
                // add a connector if the default intra VM connector isn't enough
                // getBrokerService().addConnector("tcp://0.0.0.0:61616?maximumConnections=10");

                // create the Authorization configuration to test against the roles from the JWT token
                final XBeanAuthorizationMap writeReadAdminACLs = new XBeanAuthorizationMap();
                writeReadAdminACLs.setAuthorizationEntries(new ArrayList<>(){{
                    add(new XBeanAuthorizationEntry(){{setQueue(">");                   setRead("admins"); setWrite("admins"); setAdmin("admins"); afterPropertiesSet();}});
                    add(new XBeanAuthorizationEntry(){{setQueue("USERS");               setRead("users,admins"); setWrite("users,admins"); setAdmin("users,admins"); afterPropertiesSet();}});
                    add(new XBeanAuthorizationEntry(){{setTopic("ActiveMQ.Advisory.>"); setRead("*"); setWrite("*"); setAdmin("*"); afterPropertiesSet();}});
                }});
                writeReadAdminACLs.afterPropertiesSet();

                // Add our JWT Authentication plugin and the configured AuthorizationPlugin
                getBrokerService().setPlugins(new BrokerPlugin[]{
                    new JwtAuthenticationPlugin(),
                    new AuthorizationPlugin(writeReadAdminACLs)
                });

            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void start() {
            try {
                configure();
                getBrokerService().start();

            } catch (final Exception ex) {
                throw new RuntimeException("Exception encountered starting embedded ActiveMQ broker: {}" + this.getBrokerName(), ex);
            }

            getBrokerService().waitUntilStarted();
        }
    };

    @Test
    public void noToken() throws Exception {
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());

        final String destinationName = "NO_TOKEN";

        try {
            send(cf, destinationName, 1, 10);
            fail("Should have failed because anonymous are allowed to connect, but authorization will fail.");
        } catch (final JMSException e) {
            // ok
        }

        // we should have 0 message actually in the queue because of authorization plugin
        Assert.assertEquals(0, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void badFormattedToken() throws Exception {
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName("bla");
        cf.setPassword("");

        final String destinationName = "BAD_TOKEN";

        try {
            send(cf, destinationName, 10, 20);
            fail("Should have failed because token is invalid.");
        } catch (final JMSException e) {
            // ok
        }

        // we should have zero messages actually in the queue
        Assert.assertEquals(0, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void valid() throws Exception {
        final String token = TokenUtils.token("bob", Arrays.asList("admins"));
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName(token);
        cf.setPassword("");

        final int numberOfMessages = 10;
        final String destinationName = "QUEUE";

        send(cf, destinationName, numberOfMessages, 100);

        // we should have zero messages actually in the queue
        Assert.assertEquals(numberOfMessages, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void validCorrectGroup() throws Exception {
        final String token = TokenUtils.token("captain", Arrays.asList("users"));
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName(token);
        cf.setPassword("");

        final int numberOfMessages = 10;
        final String destinationName = "USERS";

        send(cf, destinationName, numberOfMessages, 100);

        // we should have zero messages actually in the queue
        Assert.assertEquals(numberOfMessages, customizedBroker.getMessageCount(destinationName));
    }

    @Test
    public void validWrongGroup() throws Exception {
        final String token = TokenUtils.token("tony", Arrays.asList("consumers"));
        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(customizedBroker.getVmURI());
        cf.setUserName(token);
        cf.setPassword("");

        final int numberOfMessages = 10;
        final String destinationName = "USERS";

        try {
            send(cf, destinationName, numberOfMessages, 100);
        } catch (JMSException e) {
            // ok
        }

        // we should have zero messages actually in the queue
        Assert.assertEquals(0, customizedBroker.getMessageCount(destinationName));
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
