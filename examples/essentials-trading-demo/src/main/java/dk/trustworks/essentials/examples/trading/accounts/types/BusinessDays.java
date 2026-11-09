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

package dk.trustworks.essentials.examples.trading.accounts.types;

import java.time.*;

/**
 * Weekday arithmetic for windows counted in business days, such as a deposit's return window. Weekends only: the
 * exchange's holiday calendar is not known here, so a holiday counts as a business day. Good enough for a window that
 * only decides when money becomes withdrawable; trade and settlement dates always come from the exchange (D23).
 */
public final class BusinessDays {
    private BusinessDays() {
    }

    public static LocalDate add(LocalDate date, int businessDays) {
        if (businessDays < 0) {
            throw new IllegalArgumentException("businessDays must be >= 0, was " + businessDays);
        }
        var result = date;
        var added  = 0;
        while (added < businessDays) {
            result = result.plusDays(1);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return result;
    }
}
