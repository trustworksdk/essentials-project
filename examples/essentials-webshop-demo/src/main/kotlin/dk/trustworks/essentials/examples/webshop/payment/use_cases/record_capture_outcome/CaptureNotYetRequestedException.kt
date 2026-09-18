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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.record_capture_outcome

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId

/**
 * The webhook got here first.
 *
 * A gateway can call back faster than our own transaction commits, so an outcome can arrive for a capture this
 * stream does not know about yet. That is a **timing** problem, not a bad message: the same message will be
 * processable in a moment, so it has to be retried.
 *
 * The exception type is load-bearing. `IllegalArgumentException` - which Kotlin's `require(...)` and this
 * repository's `FailFast` guards both throw - is on the framework's permanent-error list, so it dead-letters on
 * the first delivery no matter what the redelivery policy says. This is a plain `RuntimeException` precisely so
 * that the Inbox will try again.
 */
class CaptureNotYetRequestedException(val orderId: OrderId) :
    RuntimeException("No capture has been requested for order '$orderId' yet - the outcome arrived first")
