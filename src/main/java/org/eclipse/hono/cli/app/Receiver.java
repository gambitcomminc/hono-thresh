/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.cli.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.BiConsumer;

import javax.annotation.PostConstruct;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.util.MessageHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;

/**
 * A command line client for receiving messages from via Hono's north bound Telemetry and/or Event API
 * <p>
 * Messages are output to stdout.
 * <p>
 * Note that this example intentionally does not support Command &amp; Control and rather is the most simple version of a
 * receiver for downstream data. Please refer to the documentation of Command &amp; Control for the example that supports
 * it (found in the User Guide section).
 */
@Component
@Profile("receiver")
public class Receiver extends AbstractApplicationClient {

    private static final String TYPE_TELEMETRY = "telemetry";
    private static final String TYPE_EVENT = "event";
    private static final String TYPE_ALL = "all";

    /**
     * @author Sucharu Sharma
     * @copyright Gambit Communications, Inc.
     */
    private static int total_tele = 0;
    private static int last_tele = 0;
    private static ArrayList<String> serial_nos = new ArrayList<String>();
    private static ArrayList<String> exceeded_sn = new ArrayList<String>();

    /**
     * The type of messages to create a consumer for.
     */
    @Value(value = "${message.type}")
    protected String messageType;

    /**
     * Bi consumer to handle messages based on endpoint.
     */
    private BiConsumer<String, Message> messageHandler = (endpoint, msg) -> handleMessage(endpoint, msg);

    /**
     * Set message handler for processing adaption.
     *
     * @param messageHandler message handler.
     * @throws NullPointerException if message handlerr is {@code null}.
     */
    void setMessageHandler(final BiConsumer<String, Message> messageHandler) {
        this.messageHandler = Objects.requireNonNull(messageHandler);
    }

    /**
     * Starts this component.
     * <p>
     *
     * @return A future indicating the outcome of the startup process.
     */
    @PostConstruct
    Future<CompositeFuture> start() {
        return clientFactory.connect()
                .compose(con -> {
                    clientFactory.addReconnectListener(this::createConsumer);
                    return createConsumer(con);
                })
                .onComplete(this::handleCreateConsumerStatus);
    }

    private CompositeFuture createConsumer(final HonoConnection connection) {

        final Handler<Void> closeHandler = closeHook -> {
            log.info("close handler of consumer is called");
            vertx.setTimer(connectionRetryInterval, reconnect -> {
                log.info("attempting to re-open the consumer link ...");
                createConsumer(connection);
            });
        };

        @SuppressWarnings("rawtypes")
        final List<Future> consumerFutures = new ArrayList<>();
        if (messageType.equals(TYPE_EVENT) || messageType.equals(TYPE_ALL)) {
            consumerFutures.add(
                    clientFactory.createEventConsumer(tenantId, msg -> {
                        messageHandler.accept(TYPE_EVENT, msg);
                    }, closeHandler));
        }

        if (messageType.equals(TYPE_TELEMETRY) || messageType.equals(TYPE_ALL)) {
            consumerFutures.add(
                    clientFactory.createTelemetryConsumer(tenantId, msg -> {
                        messageHandler.accept(TYPE_TELEMETRY, msg);
                    }, closeHandler));
        }

        if (consumerFutures.isEmpty()) {
            consumerFutures.add(Future.failedFuture(
                    String.format(
                            "Invalid message type [\"%s\"]. Valid types are \"telemetry\", \"event\" or \"all\"",
                            messageType)));
        }
        return CompositeFuture.all(consumerFutures);
    }

    /**
     * Handle received message.
     *
     * Write log messages to stdout.
     *
     * @param endpoint receiving endpoint, "telemetry" or "event".
     * @param msg received message
     */
    private void handleMessage(final String endpoint, final Message msg) {
        final String deviceId = MessageHelper.getDeviceId(msg);
        final Buffer payload = MessageHelper.getPayload(msg);

        /**
         * @author Sucharu Sharma
         * @copyright Gambit Communications, Inc.
         */
        if(debugMode == 1) {
        	log.info("received {} message [device: {}, content-type: {}]: {}", endpoint, deviceId, msg.getContentType(),
                    payload);
        	if (msg.getApplicationProperties() != null) {
                log.info("... with application properties: {}", msg.getApplicationProperties().getValue());
            }
        }
        if (payload.toString().contains("temp")) {
        	total_tele = total_tele + 1;
        	final String jsonPayload = (String) payload.toJson().toString();
        	final String tempData = jsonPayload.split(",")[15];
        	final String[] tempValue = tempData.split(":");

        	final int tempVal = Integer.parseInt(tempValue[tempValue.length - 1]);
        	final String sn_token = jsonPayload.split(",")[0];
        	final String sn = sn_token.substring(6, sn_token.length());

        	if(!serial_nos.contains(sn)) {
        		serial_nos.add(sn);
        	}
        	if (tempVal > tempThresh && !exceeded_sn.contains(sn)) {
        		log.info("********** temperature threshold exceeds to {} for {}", tempVal, sn);
        		exceeded_sn.add(sn);
        	} else if(tempVal <= tempThresh && exceeded_sn.contains(sn)) {
       			exceeded_sn.remove(sn);
        	}
        }
    }

    /**
     * @author Sucharu Sharma
     * @copyright Gambit Communications, Inc.
     */
    private void print_telemetry_info () {
    	if(total_tele > 0 && serial_nos.size() > 0) {
    		if(total_tele > last_tele) {
    			log.info("{} telemetries received for {} of sensor(s)", total_tele, serial_nos.size());
    			last_tele = total_tele;
    		}
    	}
    }

    private void handleCreateConsumerStatus(final AsyncResult<CompositeFuture> startup) {
        if (startup.succeeded()) {
            log.info("Receiver [tenant: {}, mode: {}] created successfully, hit ctrl-c to exit", tenantId,
                    messageType);

            /**
             * @author Sucharu Sharma
             * @copyright Gambit Communications, Inc.
             */
            log.info("Receiver [threshold value set to: {}]", tempThresh);
            if(debugMode == 1) {
            	log.info("... Debug mode: On");
            }
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					print_telemetry_info();					
				}
			}, 0, timeInterval);
        } else {
            log.error("Error occurred during initialization of receiver: {}", startup.cause().getMessage());
            vertx.close();
        }
    }
}
