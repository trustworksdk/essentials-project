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

package dk.trustworks.essentials.examples.trading._demo_harness;

/**
 * One funding load run: what was requested, what the gateway answered, and how long a payment took from the request to
 * its booking on the ledger.
 */
public record FundingLoadResultView(String runId,
                                    String state,
                                    int customers,
                                    int depositsPerCustomer,
                                    int withdrawalsPerCustomer,
                                    int threads,
                                    long requested,
                                    long refused,
                                    long conflicts,
                                    long setupMs,
                                    long requestMs,
                                    double requestsPerSecond,
                                    double requestLatencyP50Ms,
                                    double requestLatencyP99Ms,
                                    long drainMs,
                                    double completedPaymentsPerSecond,
                                    long completed,
                                    long failed,
                                    long booked,
                                    double depositEndToEndP50Ms,
                                    double depositEndToEndP99Ms,
                                    double withdrawalEndToEndP50Ms,
                                    double withdrawalEndToEndP99Ms,
                                    String error) {
}
