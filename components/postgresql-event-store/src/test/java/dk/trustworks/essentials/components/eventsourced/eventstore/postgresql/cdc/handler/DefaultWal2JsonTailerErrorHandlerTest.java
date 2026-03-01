/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.handler;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWal2JsonTailerErrorHandlerTest {

    private final DefaultWal2JsonTailerErrorHandler errorHandler = new DefaultWal2JsonTailerErrorHandler();

    @Test
    void message_illegal_argument_returns_continue() {
        var decision = errorHandler.onMessageError("slot_a", "{}", new IllegalArgumentException("bad row"));

        assertThat(decision).isEqualTo(Wal2JsonTailerErrorHandler.Decision.CONTINUE);
    }

    @Test
    void message_io_returns_retry_connection() {
        var decision = errorHandler.onMessageError("slot_a", "{}", new IOException("connection dropped"));

        assertThat(decision).isEqualTo(Wal2JsonTailerErrorHandler.Decision.RETRY_CONNECTION);
    }

    @Test
    void stream_error_returns_retry_connection() {
        var decision = errorHandler.onStreamError("slot_a", new RuntimeException("unexpected"));

        assertThat(decision).isEqualTo(Wal2JsonTailerErrorHandler.Decision.RETRY_CONNECTION);
    }
}
