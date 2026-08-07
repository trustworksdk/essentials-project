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

package dk.trustworks.essentials.examples.trading.accounts;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading-demo.accounts.closing-books")
public class TradingAccountClosingBooksProperties {
    private ClosingBooksDefaultPolicyType mode;
    private Long eventThreshold;
    private ClosingBooksTimeBoundary timeBoundary;
    private String zoneId;
    private Integer intervalDays;

    public ClosingBooksDefaultPolicyType getMode() {
        return mode;
    }

    public void setMode(ClosingBooksDefaultPolicyType mode) {
        this.mode = mode;
    }

    public Long getEventThreshold() {
        return eventThreshold;
    }

    public void setEventThreshold(Long eventThreshold) {
        this.eventThreshold = eventThreshold;
    }

    public ClosingBooksTimeBoundary getTimeBoundary() {
        return timeBoundary;
    }

    public void setTimeBoundary(ClosingBooksTimeBoundary timeBoundary) {
        this.timeBoundary = timeBoundary;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public Integer getIntervalDays() {
        return intervalDays;
    }

    public void setIntervalDays(Integer intervalDays) {
        this.intervalDays = intervalDays;
    }
}
