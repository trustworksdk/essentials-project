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
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import java.time.Duration;
import java.util.*;

/**
 * What a load-driver run does (design phase 6 §2-§4), from {@code --key=value} arguments; every field has a small
 * default, so a bare run is a short smoke run (Q-3: start small, make everything configurable).
 *
 * @param baseUrls            the brokerage instances; customer {@code n} is pinned to {@code baseUrls[n % size]}
 * @param exchangeUrl         the exchange-simulator, for its clock and its {@code BusinessDayClosed} events
 * @param gatewayUrl          the payment-gateway, for fault injection on its webhooks
 * @param population          customers {@code LDA-0} .. {@code LDA-<population-1>}, registered and funded once, reused
 * @param activePerDay        customers active in a business day, drawn afresh at each close
 * @param ordersPerSecond     the steady order rate
 * @param burstOrdersPerSecond the order rate at each business day's open, for {@code burstFraction} of a business day's
 *                            real length after it
 * @param readsPerOrder       account reads a customer makes before each order
 * @param paymentsPerSecond   deposits and withdrawals together
 * @param businessDays        closes to run for
 * @param maxInFlight         concurrent customer actions; above it an action is skipped and counted, not queued
 * @param fault               {@code none}, or the fault of design phase 6 §10 to run under the load: {@code F-1} damaged,
 *                            duplicated and reordered webhook copies from both simulators for the whole run; {@code F-2}
 *                            every instance answers its webhooks {@code 503} for {@code faultDuration} from the open of
 *                            business day {@code faultAtDay}; {@code F-3} {@code run-faults.sh} kills the instance
 *                            ({@code SIGKILL}) at the open of business day {@code faultAtDay} and starts it again 10 s
 *                            later; {@code F-4} {@code run-faults.sh} restarts the
 *                            {@code faultTarget} simulator at the open of business day {@code faultAtDay}; {@code F-5}
 *                            reconciliation breaks seeded by the simulators (started with them by {@code run-faults.sh}),
 *                            each found one for one and turned into a case
 * @param faultAtDay          the business day a fault with a window opens it in, at that day's open (day 1 is the first)
 * @param faultDuration       how long a fault with a window keeps it open
 * @param faultTarget         F-4's simulator: {@code exchange} or {@code gateway}
 * @param faultInstance       F-3's instance, 1-based into {@code baseUrls}
 */
public record LoadDriverSettings(List<String> baseUrls,
                                 String exchangeUrl,
                                 String gatewayUrl,
                                 String operatorUser,
                                 String operatorPassword,
                                 int population,
                                 int activePerDay,
                                 double ordersPerSecond,
                                 double burstOrdersPerSecond,
                                 double burstFraction,
                                 int readsPerOrder,
                                 double paymentsPerSecond,
                                 int businessDays,
                                 int marketBuyPercent,
                                 int limitBuyPercent,
                                 int sellPercent,
                                 int restingPercent,
                                 int cancelRestingPercent,
                                 int depositPercent,
                                 int maxQuantity,
                                 List<String> instruments,
                                 long seedCash,
                                 int setupThreads,
                                 int maxInFlight,
                                 Duration drainTimeout,
                                 String runToken,
                                 String reportDirectory,
                                 String fault,
                                 int faultAtDay,
                                 Duration faultDuration,
                                 String faultTarget,
                                 int faultInstance) {
    static final Set<String> FAULTS = Set.of("none", "F-1", "F-2", "F-3", "F-4", "F-5");


    public static LoadDriverSettings fromArguments(String[] args) {
        var values = new HashMap<String, String>();
        for (var arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("Expected --key=value, got '" + arg + "'");
            }
            values.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
        }
        var settings = new LoadDriverSettings(
                List.of(take(values, "base-urls", "http://localhost:8080").split(",")),
                take(values, "exchange-url", "http://localhost:18080"),
                take(values, "gateway-url", "http://localhost:18081"),
                take(values, "operator-user", "operator@demo.local"),
                take(values, "operator-password", "operator-demo-password"),
                Integer.parseInt(take(values, "population", "200")),
                Integer.parseInt(take(values, "active-per-day", "100")),
                Double.parseDouble(take(values, "orders-per-second", "10")),
                Double.parseDouble(take(values, "burst-orders-per-second", "40")),
                Double.parseDouble(take(values, "burst-fraction", "0.1")),
                Integer.parseInt(take(values, "reads-per-order", "3")),
                Double.parseDouble(take(values, "payments-per-second", "1")),
                Integer.parseInt(take(values, "business-days", "5")),
                Integer.parseInt(take(values, "market-buy-percent", "45")),
                Integer.parseInt(take(values, "limit-buy-percent", "20")),
                Integer.parseInt(take(values, "sell-percent", "20")),
                Integer.parseInt(take(values, "resting-percent", "15")),
                Integer.parseInt(take(values, "cancel-resting-percent", "50")),
                Integer.parseInt(take(values, "deposit-percent", "70")),
                Integer.parseInt(take(values, "max-quantity", "10")),
                List.of(take(values, "instruments", "ACME,AAPL,MSFT,NVDA,AMZN").split(",")),
                Long.parseLong(take(values, "seed-cash", "100000")),
                Integer.parseInt(take(values, "setup-threads", "16")),
                Integer.parseInt(take(values, "max-in-flight", "256")),
                Duration.parse(take(values, "drain-timeout", "PT10M")),
                take(values, "run-token", Long.toString(System.currentTimeMillis() / 1000 % 1_000_000, 36)),
                take(values, "report-directory", "target/load-driver"),
                take(values, "fault", "none"),
                Integer.parseInt(take(values, "fault-at-day", "4")),
                Duration.parse(take(values, "fault-duration", "PT60S")),
                take(values, "fault-target", "exchange"),
                Integer.parseInt(take(values, "fault-instance", "1")));
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Unknown settings: " + values.keySet());
        }
        if (settings.marketBuyPercent + settings.limitBuyPercent + settings.sellPercent + settings.restingPercent != 100) {
            throw new IllegalArgumentException("market-buy, limit-buy, sell and resting percents must add up to 100");
        }
        if (!FAULTS.contains(settings.fault)) {
            throw new IllegalArgumentException("fault must be one of " + new TreeSet<>(FAULTS) + ", got '" + settings.fault + "'");
        }
        if (!Set.of("exchange", "gateway").contains(settings.faultTarget)) {
            throw new IllegalArgumentException("fault-target must be exchange or gateway, got '" + settings.faultTarget + "'");
        }
        if (settings.faultInstance < 1 || settings.faultInstance > settings.baseUrls.size()) {
            throw new IllegalArgumentException("fault-instance must name one of the " + settings.baseUrls.size() + " base-urls (1-based)");
        }
        if (settings.activePerDay > settings.population) {
            throw new IllegalArgumentException("active-per-day must not exceed population");
        }
        return settings;
    }

    /**
     * {@code LD-<runToken>-}: every order and payment of this run starts with it.
     */
    public String runPrefix() {
        return "LD-" + runToken + "-";
    }

    private static String take(Map<String, String> values, String key, String defaultValue) {
        var value = values.remove(key);
        return value != null ? value : defaultValue;
    }
}
