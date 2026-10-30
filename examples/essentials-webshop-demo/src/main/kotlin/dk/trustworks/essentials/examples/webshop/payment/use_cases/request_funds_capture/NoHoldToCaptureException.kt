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

package dk.trustworks.essentials.examples.webshop.payment.use_cases.request_funds_capture

import dk.trustworks.essentials.examples.webshop.sales.types.OrderId

/**
 * Asking to capture funds that were never authorized is a defect, not a timing problem - this slice is only
 * ever reached for an order whose hold was placed, so it does not become true by waiting and must not be
 * retried into a dead letter.
 */
class NoHoldToCaptureException(val orderId: OrderId) :
    RuntimeException("Order '$orderId' has no credit-card hold to capture")
