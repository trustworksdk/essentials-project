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

/**
 * Thrown when a row-change payload references a relation whose schema the decoder has not
 * cached yet.
 * <p>
 * This is a <b>recoverable</b> decode failure, which is why it is a distinct type rather than
 * a plain {@link IllegalStateException}: the schema is carried by a separate WAL message
 * (pgoutput's {@code 'R'} RELATION message) that is retained in the CDC inbox, so the decoder's
 * in-memory cache can be rebuilt from the inbox and the decode retried. The dispatcher does
 * exactly that before falling back to the configured {@code PoisonPolicy} — see
 * {@code CdcDispatcher#decodeWithSchemaRecovery}.
 * <p>
 * Extends {@link IllegalStateException} so existing callers that catch the broader type keep
 * working.
 */
public class MissingRelationMetadataException extends IllegalStateException {
    private final int relationId;

    public MissingRelationMetadataException(String message, int relationId) {
        super(message);
        this.relationId = relationId;
    }

    /**
     * The pgoutput relation OID whose metadata was missing.
     */
    public int getRelationId() {
        return relationId;
    }
}
