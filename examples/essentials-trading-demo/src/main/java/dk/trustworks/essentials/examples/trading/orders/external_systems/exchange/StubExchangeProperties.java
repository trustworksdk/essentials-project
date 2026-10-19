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

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.List;

/**
 * The stub exchange's behaviour. Registered by {@code OrdersConfiguration}.
 */
@ConfigurationProperties(prefix = "trading-demo.orders.stub-exchange")
public class StubExchangeProperties {
    public enum FillMode {
        /**
         * Match on arrival against the current quote (see {@code StubExchangeGateway}); unmarketable limit orders rest.
         */
        IMMEDIATE,
        /**
         * Accept and never fill; a cancel request is confirmed. For the cancel path and for open orders.
         */
        NEVER
    }

    private FillMode     fillMode            = FillMode.IMMEDIATE;
    /**
     * Instruments whose orders rest unfilled whatever the {@code fillMode}, so open orders and the cancel path can be shown
     * next to filled ones.
     */
    private List<String> restingInstruments  = List.of();
    /**
     * Instruments the stub refuses, so the rejection path can be shown without a random outcome.
     */
    private List<String> rejectedInstruments = List.of();
    /**
     * The trade date is today in this zone; settlement is one business day later (T+1). The real exchange sends both
     * (SR-29).
     */
    private ZoneId       zoneId              = ZoneId.of("America/New_York");

    public FillMode getFillMode() {
        return fillMode;
    }

    public void setFillMode(FillMode fillMode) {
        this.fillMode = fillMode;
    }

    public List<String> getRestingInstruments() {
        return restingInstruments;
    }

    public void setRestingInstruments(List<String> restingInstruments) {
        this.restingInstruments = restingInstruments;
    }

    public List<String> getRejectedInstruments() {
        return rejectedInstruments;
    }

    public void setRejectedInstruments(List<String> rejectedInstruments) {
        this.rejectedInstruments = rejectedInstruments;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }
}
