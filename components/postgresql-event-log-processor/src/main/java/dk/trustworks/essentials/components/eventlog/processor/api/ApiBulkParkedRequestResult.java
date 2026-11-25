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

package dk.trustworks.essentials.components.eventlog.processor.api;

import dk.trustworks.essentials.components.eventlog.BulkParkedResult;

/**
 * What became of a retry-all or discard-all request: {@link BulkParkedResult}.
 *
 * @param subscriptionFound whether a progress row with the key exists; {@code false} stores nothing
 * @param requested         how many parked heads the request was stored on; {@code 0} when the subscription had none
 * @param nudgedLocally     whether the instance that took the request runs the subscription and was told to serve the
 *                          requests at once; otherwise the instance running it serves them at its next parked check
 */
public record ApiBulkParkedRequestResult(boolean subscriptionFound, int requested, boolean nudgedLocally) {
    public static ApiBulkParkedRequestResult from(BulkParkedResult result) {
        return new ApiBulkParkedRequestResult(result.subscriptionFound(), result.requested(), result.nudgedLocally());
    }
}
