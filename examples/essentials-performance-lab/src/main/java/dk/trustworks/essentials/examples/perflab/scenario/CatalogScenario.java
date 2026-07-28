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

package dk.trustworks.essentials.examples.perflab.scenario;

import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CatalogScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(CatalogScenario.class);

    @Override
    public String name() {
        return "catalog";
    }

    @Override
    public String description() {
        return "Lists available lab scenarios and current runtime settings";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) {
        log.info("Essentials Performance Lab configuration: mode={}, scenario={}, warmup={}, duration={}, producerThreads={}, subscriberCount={}, queueCount={}, aggregateCardinality={}, randomSeed={}, metricsOutputFile={}",
                 properties.getMode(),
                 properties.getScenario(),
                 properties.getWarmup(),
                 properties.getDuration(),
                 properties.getProducerThreads(),
                 properties.getSubscriberCount(),
                 properties.getQueueCount(),
                 properties.getAggregateCardinality(),
                 properties.getRandomSeed(),
                 properties.getMetricsOutputFile());
    }
}
