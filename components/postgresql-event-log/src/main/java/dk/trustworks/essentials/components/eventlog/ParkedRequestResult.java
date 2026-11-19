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
 * What became of an operator's retry or discard request
 */
public enum ParkedRequestResult {
    /** Stored on the parked head; the node that runs the subscription serves it at its next parked check */
    ACCEPTED,
    /** Served by this node's running subscription: the head was retried (or discarded) and its aggregate released */
    DONE,
    /** Served by this node's running subscription and the retry failed again: the head stays parked, one attempt more */
    FAILED,
    /** No parked head at that global order: never parked, held behind another head, or already served */
    NOT_A_HEAD,
    /** No progress row with that key */
    NO_SUCH_SUBSCRIPTION
}
