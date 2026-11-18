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
 * One order-flow run. {@code ordersPerSecond} is placement throughput; {@code completedOrdersPerSecond} is placed orders
 * over the time until the last one was booked and released. {@code endToEnd*} is placement to {@code TradeBooked}, per
 * filled order. {@code restedUnexpectedly} counts orders priced to fill that rested at the exchange and the run
 * cancelled to drain - non-zero means the pipeline was slow enough for the market to move past them.
 */
public record OrderFlowResultView(String runId,
                                  String state,
                                  int customers,
                                  int ordersPerCustomer,
                                  int threads,
                                  long placed,
                                  long resting,
                                  long restedUnexpectedly,
                                  long refused,
                                  long conflicts,
                                  long setupMs,
                                  long placeMs,
                                  double ordersPerSecond,
                                  double placeLatencyP50Ms,
                                  double placeLatencyP99Ms,
                                  long drainMs,
                                  double completedOrdersPerSecond,
                                  long bookings,
                                  long releases,
                                  double endToEndP50Ms,
                                  double endToEndP99Ms,
                                  double endToEndMaxMs,
                                  String error) {
}
