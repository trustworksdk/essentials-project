/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping;

import dk.trustworks.essentials.shared.time.StopWatch;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.AbstractIntegrationTest;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.load.RecreateShippingOrderViews;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.entities.ShippingOrder;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.load.LoadOrderShippingProcessor;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.load.LoadTestShippingOrders;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.OrderId;
import dk.trustworks.essentials.spring.examples.postgresql.messaging.shipping.types.ShippingDestinationAddress;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LoadOrderShippingProcessorIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LoadOrderShippingProcessorIT.class);

    private static final int NUMBER_OF_INSERTS = 15000;

    private static final ShippingDestinationAddress LOAD_TEST_ADDRESS = ShippingDestinationAddress.builder()
                                                                                                  .setRecipientName("Load Tester")
                                                                                                  .setStreet("Load Street 1")
                                                                                                  .setZipCode("1234")
                                                                                                  .setCity("Load City")
                                                                                                  .build();

    @Autowired
    private LoadTestShippingOrders shippingOrders;

    @Autowired
    private LoadOrderShippingProcessor loadOrderShippingProcessor;

    @Test
    public void stress_test_durable_queues_and_local_eventbus() {

        insertTestData();

        log.debug("Sending RecreateShippingOrderViews");
        commandBus.send(new RecreateShippingOrderViews(OffsetDateTime.now()));

        var  queueName              = loadOrderShippingProcessor.getInbox().name().asQueueName();
        long totalMessagesQueuedFor = durableQueues.getTotalMessagesQueuedFor(queueName);
        log.debug("########## TotalMessagesQueued for               '{}': '{}'", queueName, totalMessagesQueuedFor);

        var stopWatch = StopWatch.start();
        Awaitility.waitAtMost(Duration.ofMinutes(3))
                  .pollDelay(Duration.ofSeconds(5))
                  .pollInterval(Duration.ofSeconds(10))
                  .until(() -> {
                      long currentlyQueuedFor = durableQueues.getTotalMessagesQueuedFor(queueName);
                      log.debug("===========================================================================================");
                      log.debug("########## TotalMessagesQueued for               '{}': '{}' after {}", queueName, currentlyQueuedFor, stopWatch.elapsed());
                      log.debug("########## ReceivedRecreateShippingOrderView Messages: '{}'", loadOrderShippingProcessor.getReceivedRecreateShippingOrderView());
                      log.debug("########## DeadLetterMessages for                '{}': '{}'", queueName, durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName));
                      return loadOrderShippingProcessor.getReceivedRecreateShippingOrderView().get() == totalMessagesQueuedFor;
                  });

        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(queueName)).isEqualTo(0);
    }


    @Transactional
    protected void insertTestData() {
        var                 stopWatch = StopWatch.start();
        var                 batchSize = 100;
        List<ShippingOrder> batch     = new ArrayList<>(batchSize);
        for (int i = 0; i < NUMBER_OF_INSERTS; i++) {
            // The entity has no setters any more - see entities/ShippingOrder and the BC's CLAUDE.md
            batch.add(new ShippingOrder(OrderId.random().toString(), LOAD_TEST_ADDRESS));

            if (batch.size() == batchSize) {
                try {
                    shippingOrders.saveAll(batch);
                } catch (Exception e) {
                    log.error("Failed to insert batch: {}", e.getMessage(), e);
                    throw e;
                }
                batch = new ArrayList<>(batchSize);
            }

            if (i % 1000 == 0) {
                log.debug("Have inserted {} ShippingOrders so far", i);
            }
        }
        if (!batch.isEmpty()) {
            log.debug("Have inserted remaining {} ShippingOrders", batch.size());
            shippingOrders.saveAll(batch);
        }
        long count = shippingOrders.count();
        log.info("Took '{}' to insert '{}' shipping orders count '{}'", stopWatch.stop(), NUMBER_OF_INSERTS, count);
    }

}
