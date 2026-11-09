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
 * One ledger load run, as {@code GET /api/admin/ledger-load} reports it. Times in milliseconds.
 *
 * @param commandLatencyP50Ms ORDERED: time inside the command handler per command (queue wait excluded — see
 *                            {@code drainMs}). DIRECT: one whole cycle (4 commands) including its retries.
 * @param drainMs             from the first command sent or enqueued to the last one handled
 * @param viewCatchUpMs       from the last command handled until {@code account_activity} holds every event of the run
 * @param conflicts           optimistic-concurrency conflicts seen (each one retried)
 */
public record LedgerLoadResultView(String runId,
                                   LedgerLoadGenerator.Mode mode,
                                   String state,
                                   int accounts,
                                   int hotAccounts,
                                   int cycles,
                                   long commands,
                                   long commandsHandled,
                                   long setupMs,
                                   long enqueueMs,
                                   long drainMs,
                                   double commandsPerSecond,
                                   double commandLatencyP50Ms,
                                   double commandLatencyP99Ms,
                                   double commandLatencyMaxMs,
                                   long conflicts,
                                   long failures,
                                   long viewCatchUpMs,
                                   String error) {
}
