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

import java.sql.*;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.*;

/**
 * A lazily read {@link ResultSet} as an ordered {@link Stream} ({@code loadEventsByGlobalOrder}): a row
 * is mapped when the stream asks for it, so with a fetch size on a connection with autocommit off only one fetch is
 * held at a time. Closing the stream closes the result set and runs {@code onClose}, once; the caller must close it. A
 * failure while reading is an {@link IllegalStateException} with the {@link SQLException} as its cause.
 */
final class ResultSetStream {
    /** Maps the current row */
    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    /** Releases what the result set came from: its statement, a borrowed connection */
    @FunctionalInterface
    interface Release {
        void run() throws SQLException;
    }

    private ResultSetStream() {
    }

    static <T> Stream<T> of(ResultSet resultSet, RowMapper<T> mapper, Release release) {
        var spliterator = new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            private boolean done;

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                if (done) {
                    return false;
                }
                try {
                    if (!resultSet.next()) {
                        done = true;
                        return false;
                    }
                    action.accept(mapper.map(resultSet));
                    return true;
                } catch (SQLException e) {
                    done = true;
                    throw new IllegalStateException("Could not read the next event", e);
                }
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(() -> {
            try {
                try {
                    resultSet.close();
                } finally {
                    release.run();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not close the event read", e);
            }
        });
    }

    /**
     * @return {@code ordered} and {@code others} (both sorted by {@code order}, disjoint) as one sorted stream; closing it
     * closes {@code ordered}
     */
    static <T> Stream<T> merge(Stream<T> ordered, java.util.List<T> others, java.util.Comparator<? super T> order) {
        var iterator = ordered.iterator();
        var spliterator = new Spliterators.AbstractSpliterator<T>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            private int next;
            private T   pending;

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                if (pending == null && iterator.hasNext()) {
                    pending = iterator.next();
                }
                var other = next < others.size() ? others.get(next) : null;
                if (pending == null && other == null) {
                    return false;
                }
                if (other == null || (pending != null && order.compare(pending, other) <= 0)) {
                    action.accept(pending);
                    pending = null;
                } else {
                    action.accept(other);
                    next++;
                }
                return true;
            }
        };
        return StreamSupport.stream(spliterator, false).onClose(ordered::close);
    }
}
