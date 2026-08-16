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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcDeliveryMode;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;

import java.util.List;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * How a {@link WalReplicationTailer} hands decoded WAL payloads onwards — and, being sealed, <em>what it needs in
 * order to do so</em>.
 * <p>
 * This replaces a {@link CdcDeliveryMode} enum carried alongside a {@link CdcInboxRepository} and an
 * {@code Optional<Consumer<List<PersistedEvent>>>}. That shape let a caller name {@link CdcDeliveryMode#DIRECT}
 * without supplying a consumer, so the tailer re-validated the combination at construction time
 * ({@code "directOnEvents cannot be null in DIRECT delivery mode"}). It is one value, not three: naming the mode and
 * supplying its collaborator are the same act, so the illegal combination is now unrepresentable and the runtime check
 * is gone.
 *
 * @see #inbox(CdcInboxRepository)
 * @see #direct(Consumer)
 */
public sealed interface CdcDelivery {

    /**
     * @return the equivalent {@link CdcDeliveryMode}, for the parts of the tailer and the admin API that still report
     *         delivery as an enum
     */
    CdcDeliveryMode mode();

    /**
     * Durable delivery: the tailer writes each payload to a staging table and a {@link CdcDispatcher} polls it. This
     * is the mode that survives a tailer restart, because the payload is committed before it is decoded.
     *
     * @param inboxRepository the staging table the tailer writes to
     * @return the delivery
     */
    static CdcDelivery inbox(CdcInboxRepository inboxRepository) {
        return new Inbox(inboxRepository);
    }

    /**
     * In-process delivery: the tailer decodes each payload and calls {@code onEvents} inline. Faster, but a payload
     * consumed and then lost to a crash is not redelivered — the WAL position has already moved on.
     *
     * @param onEvents the consumer invoked with each decoded batch
     * @return the delivery
     */
    static CdcDelivery direct(Consumer<List<PersistedEvent>> onEvents) {
        return new Direct(onEvents);
    }

    /**
     * @param inboxRepository the staging table the tailer writes each WAL payload to
     * @see #inbox(CdcInboxRepository)
     */
    record Inbox(CdcInboxRepository inboxRepository) implements CdcDelivery {
        public Inbox {
            requireNonNull(inboxRepository, "inboxRepository cannot be null");
        }

        @Override
        public CdcDeliveryMode mode() {
            return CdcDeliveryMode.INBOX;
        }
    }

    /**
     * @param onEvents the consumer invoked, on the tailer's own thread, with each decoded batch of events
     * @see #direct(Consumer)
     */
    record Direct(Consumer<List<PersistedEvent>> onEvents) implements CdcDelivery {
        public Direct {
            requireNonNull(onEvents, "onEvents cannot be null");
        }

        @Override
        public CdcDeliveryMode mode() {
            return CdcDeliveryMode.DIRECT;
        }
    }
}
