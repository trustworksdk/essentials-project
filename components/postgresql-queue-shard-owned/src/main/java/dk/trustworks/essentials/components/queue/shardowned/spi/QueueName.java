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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * What a queue is called.
 * <p>
 * The engine addresses a queue by an interned {@code smallint}, because a two-byte column in the
 * primary key of every row is what keeps the hot index dense — that is a storage decision and it does
 * not change. A name is what a <em>caller</em> should have to know, and until now there was nowhere
 * to put one: each process kept its own name-to-id mapping, which is a shared contract living in
 * several places at once.
 * <p>
 * <b>Not a {@code CharSequenceType}</b>, which is the project's usual base for a type like this. That
 * class lives in the {@code types} module, which carries kotlin-reflect and kotlin-stdlib at compile
 * scope; this module depends on {@code shared} alone and the trade is not worth it for one wrapper.
 * The behaviour that matters here — value equality, validation at construction, a {@code toString}
 * that is just the name — is what a record gives for free.
 * <p>
 * The name reaches SQL only as a bind parameter, never concatenated into a statement, so the length
 * bound below is about keeping identifiers sane rather than about injection.
 */
public record QueueName(String value) implements Comparable<QueueName> {

    /** Long enough for any sensible name, short enough to index and to read in a log line. */
    public static final int MAX_LENGTH = 255;

    public QueueName {
        requireNonNull(value, "No queue name provided");
        requireTrue(!value.isBlank(), "A queue name must not be blank");
        requireTrue(value.length() <= MAX_LENGTH,
                    "A queue name must be at most " + MAX_LENGTH + " characters, was " + value.length());
    }

    public static QueueName of(String value) {
        return new QueueName(value);
    }

    @Override
    public int compareTo(QueueName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
