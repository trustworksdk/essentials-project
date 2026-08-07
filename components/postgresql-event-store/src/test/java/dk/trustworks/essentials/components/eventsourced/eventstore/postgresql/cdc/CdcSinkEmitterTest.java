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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.WalReplicationWithEssentialsAggregateWal2JsonIT.ORDERS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CdcSinkEmitterTest {

    @Test
    void emit_succeeds_returns_immediately_on_OK() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any())).thenReturn(Sinks.EmitResult.OK);

        CdcSinkEmitter.tryEmit(sink, pe(1), 4, 2,
                               CdcProperties.CdcOverflowPolicy.FAIL_FAST,
                               "test", LoggerFactory.getLogger(CdcSinkEmitterTest.class));

        verify(sink, times(1)).tryEmitNext(any());
    }

    @Test
    void overflow_with_fail_fast_throws_after_exhausting_retries() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        AtomicInteger emitAttempts = new AtomicInteger();
        when(sink.tryEmitNext(any())).thenAnswer(inv -> {
            emitAttempts.incrementAndGet();
            return Sinks.EmitResult.FAIL_OVERFLOW;
        });

        assertThatThrownBy(() ->
            CdcSinkEmitter.tryEmit(sink, pe(42),
                                   /*nonSerializedMaxRetries*/ 2,
                                   /*overflowMaxRetries*/ 2,
                                   CdcProperties.CdcOverflowPolicy.FAIL_FAST,
                                   "test",
                                   LoggerFactory.getLogger(CdcSinkEmitterTest.class))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("overflow retries exhausted")
         .hasMessageContaining("globalOrder=42");

        // 1 initial + 2 retries = 3 attempts before giving up.
        assertThat(emitAttempts.get()).isEqualTo(3);
    }

    @Test
    void overflow_with_log_and_drop_does_not_throw() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any())).thenReturn(Sinks.EmitResult.FAIL_OVERFLOW);

        // With LOG_AND_DROP, retry exhaustion results in a warn log — no exception.
        CdcSinkEmitter.tryEmit(sink, pe(99),
                               2, 2,
                               CdcProperties.CdcOverflowPolicy.LOG_AND_DROP,
                               "test",
                               LoggerFactory.getLogger(CdcSinkEmitterTest.class));
    }

    @Test
    void non_serialized_failure_is_retried_then_succeeds() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any()))
                .thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED)
                .thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED)
                .thenReturn(Sinks.EmitResult.OK);

        CdcSinkEmitter.tryEmit(sink, pe(7),
                               /*nonSerializedMaxRetries*/ 5,
                               /*overflowMaxRetries*/ 0,
                               CdcProperties.CdcOverflowPolicy.FAIL_FAST,
                               "test",
                               LoggerFactory.getLogger(CdcSinkEmitterTest.class));

        verify(sink, times(3)).tryEmitNext(any());
    }

    @Test
    void non_serialized_exhaustion_with_fail_fast_throws_transient_not_generic() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any())).thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED);

        // Exhausting the non-serialized budget under FAIL_FAST must throw a TRANSIENT emit exception
        // (so the dispatcher retries the row), NOT a generic IllegalStateException (which the
        // dispatcher would treat as a conversion failure and poison a healthy row).
        assertThatThrownBy(() ->
            CdcSinkEmitter.tryEmit(sink, pe(11),
                                   /*nonSerializedMaxRetries*/ 3,
                                   /*overflowMaxRetries*/ 0,
                                   CdcProperties.CdcOverflowPolicy.FAIL_FAST,
                                   "test",
                                   LoggerFactory.getLogger(CdcSinkEmitterTest.class))
        ).isInstanceOf(CdcTransientEmitException.class)
         .isInstanceOf(CdcNonSerializedEmitException.class)
         .hasMessageContaining("non-serialized emit retries exhausted")
         .hasMessageContaining("globalOrder=11");

        verify(sink, times(3)).tryEmitNext(any());
    }

    @Test
    void non_serialized_exhaustion_with_log_and_drop_does_not_throw() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any())).thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED);

        // LOG_AND_DROP escape hatch must still apply to non-serialized exhaustion — warn, no throw.
        CdcSinkEmitter.tryEmit(sink, pe(12),
                               3, 0,
                               CdcProperties.CdcOverflowPolicy.LOG_AND_DROP,
                               "test",
                               LoggerFactory.getLogger(CdcSinkEmitterTest.class));

        verify(sink, times(3)).tryEmitNext(any());
    }

    @Test
    void zero_subscriber_is_dropped_silently() {
        @SuppressWarnings("unchecked")
        Sinks.Many<PersistedEvent> sink = mock(Sinks.Many.class);
        when(sink.tryEmitNext(any())).thenReturn(Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER);

        // Even with FAIL_FAST, zero-subscriber is a soft drop (not an error).
        CdcSinkEmitter.tryEmit(sink, pe(5),
                               4, 2,
                               CdcProperties.CdcOverflowPolicy.FAIL_FAST,
                               "test",
                               LoggerFactory.getLogger(CdcSinkEmitterTest.class));

        verify(sink, times(1)).tryEmitNext(any());
    }

    private static PersistedEvent pe(long globalOrder) {
        return PersistedEvent.from(
                EventId.random(),
                ORDERS,
                OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89"),
                new EventJSON(new JacksonJSONEventSerializer(new ObjectMapper()), EventType.of("TestEvent"), """
                                                                                                             {"type":"TestEvent","globalOrder":%d}
                                                                                                             """.formatted(globalOrder)),
                EventOrder.of(1L),
                EventRevision.of(1),
                GlobalEventOrder.of(globalOrder),
                new EventMetaDataJSON(new JacksonJSONEventSerializer(new ObjectMapper()), "", ""),
                OffsetDateTime.now(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
                                  );
    }
}
