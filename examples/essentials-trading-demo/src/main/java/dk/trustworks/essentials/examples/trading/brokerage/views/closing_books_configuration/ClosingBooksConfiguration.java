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

package dk.trustworks.essentials.examples.trading.brokerage.views.closing_books_configuration;

import dk.trustworks.essentials.examples.trading.brokerage.types.ClosingBooksSettings;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The closing-books policy currently in force, as the admin surface renders it.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 * <p>
 * <b>Why {@code String} and not the enums.</b> {@link #mode} and {@link #timeBoundary} are rendered
 * lowercase-with-hyphens — {@code end-of-month}, not {@code END_OF_MONTH} — which is the vocabulary the admin UI and
 * the pre-slice endpoint have always spoken, and the same spelling the update command accepts. The typed values live
 * on {@link ClosingBooksSettings}; this is their presentation, and the one place that conversion happens.
 * <p>
 * {@link #description} is the framework evaluator's own account of what the policy will do, which is more useful on a
 * dashboard than the five fields it is derived from.
 *
 * @param intervalDays may be {@code null} — it only means anything to the interval-based policies
 */
public record ClosingBooksConfiguration(String mode,
                                        long eventThreshold,
                                        String timeBoundary,
                                        String zoneId,
                                        Integer intervalDays,
                                        String description) {
    public ClosingBooksConfiguration {
        requireNonNull(mode, "No mode provided");
        requireNonNull(timeBoundary, "No timeBoundary provided");
        requireNonNull(zoneId, "No zoneId provided");
        requireNonNull(description, "No description provided");
    }

    /**
     * Renders one settings snapshot. Takes the whole {@link ClosingBooksSettings} rather than five arguments so the
     * six fields cannot come from two different snapshots — reading the policy field-by-field is exactly what the
     * settings record was introduced to stop.
     */
    static ClosingBooksConfiguration from(ClosingBooksSettings settings, String description) {
        requireNonNull(settings, "No settings provided");
        return new ClosingBooksConfiguration(hyphenated(settings.mode().name()),
                                             settings.eventThreshold(),
                                             hyphenated(settings.timeBoundary().name()),
                                             settings.zoneId().toString(),
                                             settings.intervalDays(),
                                             description);
    }

    private static String hyphenated(String enumName) {
        return enumName.toLowerCase().replace('_', '-');
    }
}
