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
package dk.trustworks.essentials.components.eventlog;

/**
 * The answer to a bulk request on a subscription's parked heads ({@link EventLogAdmin#retryAllParked} or
 * {@link EventLogAdmin#discardAllParked}). The request is stored on every parked head; the node that runs the
 * subscription serves them, a few per pass, as it serves a single request.
 *
 * @param subscriptionFound whether a progress row with that key exists; {@code false}: nothing was stored (as
 *                          {@link ParkedRequestResult#NO_SUCH_SUBSCRIPTION})
 * @param requested         the parked heads the request was stored on
 * @param nudgedLocally     whether this node runs and owns the subscription and was told to read the requests at once;
 *                          {@code false}: the node that runs it serves them at its next parked check (as
 *                          {@link ParkedRequestResult#ACCEPTED})
 */
public record BulkParkedResult(boolean subscriptionFound, int requested, boolean nudgedLocally) {
}
