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

package dk.trustworks.essentials.examples.trading.brokerage.types;

import dk.trustworks.essentials.types.CharSequenceType;

/**
 * The generation-specific event stream id of a trading account: {@code <logicalAccountId>#<generation>}.
 *
 * <p>Closing books does not rewrite a stream, it opens the next one. So the events of generation 1 live under
 * {@code acct-1#1} and generation 2's under {@code acct-1#2}, while {@link TradingAccountId} stays {@code acct-1}
 * across both.
 *
 * <p>The {@code #} convention is owned here, and only here. It used to be duplicated: the closing-books coordinator
 * built the id by string concatenation and the statement projection took it apart again with its own
 * {@code lastIndexOf('#')} parse, so the two could drift apart silently. {@link #of(TradingAccountId, long)} and
 * {@link #generation()} are the two halves of that convention, kept in one place.
 */
public class TradingAccountGenerationId extends CharSequenceType<TradingAccountGenerationId> {
    /**
     * Separates the logical account id from the generation number in a stream id.
     */
    public static final char GENERATION_SEPARATOR = '#';

    /**
     * The generation reported for a stream id that carries no generation suffix.
     */
    private static final long FIRST_GENERATION = 1L;

    public TradingAccountGenerationId(String value) {
        super(value);
    }

    public TradingAccountGenerationId(CharSequence value) {
        super(value);
    }

    public static TradingAccountGenerationId of(CharSequence value) {
        return new TradingAccountGenerationId(value);
    }

    /**
     * Builds the stream id for a given generation of a logical account.
     *
     * @param logicalId  the stable business id of the account
     * @param generation the generation number
     * @return {@code <logicalId>#<generation>}
     */
    public static TradingAccountGenerationId of(TradingAccountId logicalId, long generation) {
        return new TradingAccountGenerationId(logicalId.toString() + GENERATION_SEPARATOR + generation);
    }

    /**
     * The generation number encoded in this stream id.
     *
     * <p>Returns {@code 1} when there is no separator, when the separator is the last character, or when the suffix
     * does not parse as a number -- a stream id written before the convention existed reads as the first generation
     * rather than failing.
     */
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
