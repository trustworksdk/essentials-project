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

package dk.trustworks.essentials.examples.trading.identity.types;

import dk.trustworks.essentials.types.CharSequenceType;

import java.util.Locale;

/**
 * A user's email address, which is also their login name. Normalised to lower case and trimmed on construction, so two
 * spellings of one address are one value — and one row under the unique constraint that guards registration.
 * <p>
 * Validation is deliberately shallow (one {@code @} with something on both sides): the address is proven by use, not
 * by a regular expression.
 */
public class Email extends CharSequenceType<Email> {
    public Email(String value) {
        super(normalise(value));
    }

    public Email(CharSequence value) {
        super(normalise(value));
    }

    public static Email of(CharSequence value) {
        return new Email(value);
    }

    private static String normalise(CharSequence value) {
        if (value == null) {
            throw new IllegalArgumentException("No email provided");
        }
        var normalised = value.toString().strip().toLowerCase(Locale.ROOT);
        var at         = normalised.indexOf('@');
        if (at <= 0 || at != normalised.lastIndexOf('@') || at == normalised.length() - 1) {
            throw new IllegalArgumentException("Not an email address: '" + value + "'");
        }
        return normalised;
    }
}
