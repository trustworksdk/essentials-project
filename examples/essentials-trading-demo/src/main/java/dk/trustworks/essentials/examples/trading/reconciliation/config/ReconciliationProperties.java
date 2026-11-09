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

package dk.trustworks.essentials.examples.trading.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code trading-demo.reconciliation.*}.
 */
@ConfigurationProperties(prefix = "trading-demo.reconciliation")
public class ReconciliationProperties {
    /**
     * How long after a business day's cutoff an expected statement may still arrive before it counts as missing (Q-3),
     * on the simulators' clock. The simulators issue a late statement within 60 simulated minutes.
     */
    private Duration statementGrace = Duration.ofHours(2);

    /**
     * How far back, in calendar days before the closed business day, statements are checked for breaks when cases are
     * opened. Statements of open cases are always checked.
     */
    private int caseLookbackDays = 7;

    public Duration getStatementGrace() {
        return statementGrace;
    }

    public void setStatementGrace(Duration statementGrace) {
        this.statementGrace = statementGrace;
    }

    public int getCaseLookbackDays() {
        return caseLookbackDays;
    }

    public void setCaseLookbackDays(int caseLookbackDays) {
        this.caseLookbackDays = caseLookbackDays;
    }
}
