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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import dk.trustworks.essentials.types.LocalDateType;

import java.time.LocalDate;

/**
 * The banking date a deposit or withdrawal takes effect from, which is not necessarily the date it was recorded --
 * hence a named type rather than a bare {@code LocalDate}, so it cannot be confused with a timestamp.
 *
 * <p>Never use it for ordering: events are ordered by {@code EventOrder} and {@code GlobalEventOrder}, never by a
 * date carried in a payload.
 */
public class ValueDate extends LocalDateType<ValueDate> {
    @JsonCreator
    public ValueDate(LocalDate value) {
        super(value);
    }

    public static ValueDate of(LocalDate value) {
        return new ValueDate(value);
    }

    public static ValueDate today() {
        return new ValueDate(LocalDate.now());
    }

    public static ValueDate tomorrow() {
        return new ValueDate(LocalDate.now().plusDays(1));
    }
}
