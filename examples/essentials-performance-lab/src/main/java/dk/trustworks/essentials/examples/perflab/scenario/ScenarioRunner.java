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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ScenarioRunner {
    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final EssentialsPerformanceLabProperties properties;
    private final Map<String, LabScenario> scenariosByName;

    public ScenarioRunner(EssentialsPerformanceLabProperties properties, List<LabScenario> scenarios) {
        this.properties = properties;
        this.scenariosByName = scenarios.stream().collect(Collectors.toMap(LabScenario::name, s -> s));
    }

    public void run() throws Exception {
        var requested = properties.getScenario();
        var scenario = scenariosByName.get(requested);
        if (scenario == null) {
            log.error("Unknown scenario '{}'. Available scenarios: {}", requested, scenariosByName.keySet());
            return;
        }

        log.info("Running Essentials Performance Lab scenario '{}' ({}) in mode={}",
                 scenario.name(),
                 scenario.description(),
                 properties.getMode());
        scenario.run(properties);
        log.info("Completed scenario '{}'", scenario.name());
    }
}
