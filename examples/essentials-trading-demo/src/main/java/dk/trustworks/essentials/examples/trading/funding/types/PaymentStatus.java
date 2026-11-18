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

package dk.trustworks.essentials.examples.trading.funding.types;

/**
 * A deposit's or withdrawal's life: requested by the customer, initiated at the gateway, then completed or failed; a
 * completed one may be returned by the bank later.
 */
public enum PaymentStatus {
    REQUESTED,
    INITIATED,
    COMPLETED,
    FAILED,
    RETURNED;

    public boolean isFinal() {
        return this == FAILED || this == RETURNED;
    }
}
