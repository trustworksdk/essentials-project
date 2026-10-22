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

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * The stream id of one closing-books generation of an account: {@code <AccountId>#<generation>}. The convention lives
 * here, and only here, so the writer ({@code AccountsConfiguration}'s stream id generator) and every reader that parses
 * it back cannot drift apart.
 */
public class AccountGenerationId extends CharSequenceType<AccountGenerationId> {
    public static final char GENERATION_SEPARATOR = '#';

    private static final long FIRST_GENERATION = 1L;

    public AccountGenerationId(String value) {
        super(value);
    }

    public AccountGenerationId(CharSequence value) {
        super(value);
    }

    public static AccountGenerationId of(CharSequence value) {
        return new AccountGenerationId(value);
    }

    public static AccountGenerationId of(AccountId accountId, long generation) {
        return new AccountGenerationId(accountId.toString() + GENERATION_SEPARATOR + generation);
    }

    public long generation() {
        var streamId  = toString();
        var separator = streamId.lastIndexOf(GENERATION_SEPARATOR);
        if (separator < 0 || separator == streamId.length() - 1) {
            return FIRST_GENERATION;
        }
        try {
            return Long.parseLong(streamId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return FIRST_GENERATION;
        }
    }
}
