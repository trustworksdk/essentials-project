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

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One page of a subscription's parked rows (design {@code docs/plans/event-store-engine-phase8.md} §4.1), in
 * {@code global_order}
 *
 * @param rows      the rows
 * @param nextAfter the lower bound (exclusive) of the next page, if the page was full
 */
public record ParkedPage(List<ParkedRow> rows, OptionalLong nextAfter) {
    public ParkedPage {
        rows = List.copyOf(requireNonNull(rows, "No rows provided"));
        requireNonNull(nextAfter, "No nextAfter provided");
    }
}
