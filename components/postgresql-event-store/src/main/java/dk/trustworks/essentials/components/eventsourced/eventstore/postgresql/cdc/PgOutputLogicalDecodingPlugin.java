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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.PgOutputToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.WalGlobalOrdersExtractor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.PgOutputRawPayloadFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter.WalMessageFilter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * {@link LogicalDecodingPlugin} adapter for PostgreSQL built-in {@code pgoutput}.
 * <p>
 * Owns the pgoutput binary protocol decode pipeline (message + row-change decoders) and
 * the {@link PgOutputToPersistedEventConverter} that turns decoded row changes into
 * {@link PersistedEvent}s. Tailer and dispatcher delegate decode + gap extraction here —
 * no pgoutput-specific code lives outside this plugin.
 */
public final class PgOutputLogicalDecodingPlugin implements LogicalDecodingPlugin {
    public static final String PLUGIN_NAME = "pgoutput";

    private static final Logger log = LoggerFactory.getLogger(PgOutputLogicalDecodingPlugin.class);

    private final PgOutputProperties                properties;
    private final PgOutputToPersistedEventConverter converter;
    private final PgOutputMessageDecoder            messageDecoder;
    private final PgOutputRowChangeDecoder          rowChangeDecoder;

    public PgOutputLogicalDecodingPlugin(PgOutputProperties properties,
                                         PgOutputToPersistedEventConverter converter) {
        this.properties = requireNonNull(properties, "properties cannot be null");
        this.converter = requireNonNull(converter, "converter cannot be null");
        requireNonBlank(properties.getPublicationName(), "publicationName cannot be blank");
        requireTrue(properties.getProtoVersion() > 0, "protoVersion must be > 0");
        this.messageDecoder = new PgOutputMessageDecoder(properties.getProtoVersion());
        this.rowChangeDecoder = new PgOutputRowChangeDecoder();
        log.info("PgOutputLogicalDecodingPlugin created");
    }

    @Override
    public String pluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Optional<String> unusableReason(Handle handle) {
        if (!PostgresqlUtil.isOutputPluginUsable(handle, pluginName())) {
            return Optional.of("pgoutput plugin not usable");
        }
        if (!PostgresqlUtil.isPublicationAvailable(handle, properties.getPublicationName())) {
            return Optional.of("pgoutput publication '" + properties.getPublicationName() + "' does not exist");
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> slotOptions() {
        return Map.of(
                "proto_version", properties.getProtoVersion(),
                "publication_names", properties.getPublicationName(),
                "binary", properties.isBinary(),
                "messages", properties.isMessages()
        );
    }

    @Override
    public List<PersistedEvent> decode(byte[] payloadBytes) {
        var rowChanges = decodeRowChanges(payloadBytes);
        if (rowChanges.isEmpty()) return List.of();
        var events = new ArrayList<PersistedEvent>(rowChanges.size());
        for (var rowChange : rowChanges) {
            converter.convertIfRelevant(rowChange).ifPresent(events::add);
        }
        return events;
    }

    @Override
    public List<WalGlobalOrdersExtractor.Gap> extractGaps(byte[] payloadBytes) {
        var rowChanges = decodeRowChanges(payloadBytes);
        if (rowChanges.isEmpty()) return List.of();
        var gaps = new ArrayList<WalGlobalOrdersExtractor.Gap>(rowChanges.size());
        for (var rowChange : rowChanges) {
            converter.extractGap(rowChange).ifPresent(gaps::add);
        }
        return gaps;
    }

    @Override
    public void prepare(Handle handle, Supplier<Set<String>> eventStreamTableNames) {
        var mgmt = properties.getPublication();
        if (mgmt == null || !mgmt.isAutoManage()) return;

        String publicationName = properties.getPublicationName();
        try {
            var pubInfoOpt = PostgresqlUtil.getPublicationInfo(handle, publicationName);
            if (pubInfoOpt.isEmpty()) {
                createPublication(handle, publicationName, mgmt.getMode(), eventStreamTableNames.get());
                return;
            }
            var pubInfo = pubInfoOpt.get();
            if (pubInfo.forAllTables()) {
                // FOR ALL TABLES is already the broadest possible membership; nothing to add.
                log.info("publication '{}' is FOR ALL TABLES; auto-manage has nothing to do", publicationName);
                return;
            }
            // Explicit-list publication — add any registered event-stream tables that aren't
            // already members. Only meaningful when mode is FOR_TABLE_LIST; a FOR_ALL_TABLES
            // auto-manage config that finds an explicit-list publication doesn't try to
            // convert it (that would require DROP+CREATE and is destructive).
            if (mgmt.getMode() != PgOutputProperties.PublicationManagement.Mode.FOR_TABLE_LIST) {
                log.warn("publication '{}' exists with explicit member list but auto-manage.mode={}; " +
                                 "will not convert to FOR ALL TABLES (that would require DROP+CREATE). " +
                                 "Change mode to FOR_TABLE_LIST or drop the publication manually.",
                         publicationName, mgmt.getMode());
                return;
            }
            Set<String> registered = eventStreamTableNames.get();
            Set<String> missing = diffMissing(pubInfo.tableMembers(), registered);
            if (missing.isEmpty()) {
                log.debug("publication '{}' already covers all {} registered event-stream tables",
                          publicationName, registered.size());
                return;
            }
            addTablesToPublication(handle, publicationName, missing);
        } catch (Exception e) {
            // Swallow — auto-manage is best-effort. Log a loud WARN with the remediation SQL so
            // an operator can run it manually, and continue the handshake. The tailer will
            // still connect; if the publication really isn't usable, the existing startup
            // diagnostic logs will call that out.
            log.warn("pgoutput publication auto-manage failed for '{}' (likely missing privileges) — " +
                             "continuing without auto-management. Remediation: run one of " +
                             "'CREATE PUBLICATION {} FOR ALL TABLES;' (requires superuser) or " +
                             "'CREATE PUBLICATION {} FOR TABLE <event-stream-tables>;' (requires table ownership). " +
                             "Error: {}",
                     publicationName, publicationName, publicationName, e.getMessage());
        }
    }

    /**
     * Create the publication with either a FOR-ALL-TABLES clause (requires superuser) or an
     * explicit table list (requires only table ownership). Sanity-checks the table names to
     * prevent SQL injection since they come from user-registered aggregate config.
     */
    private void createPublication(Handle handle,
                                   String publicationName,
                                   PgOutputProperties.PublicationManagement.Mode mode,
                                   Set<String> registeredTables) {
        if (mode == PgOutputProperties.PublicationManagement.Mode.FOR_ALL_TABLES) {
            handle.execute("CREATE PUBLICATION " + quoteIdentifier(publicationName) + " FOR ALL TABLES");
            log.info("Created pgoutput publication '{}' FOR ALL TABLES (auto-manage)", publicationName);
            return;
        }
        // FOR_TABLE_LIST
        if (registeredTables.isEmpty()) {
            // An empty-list publication isn't valid SQL. Create FOR ALL TABLES as a fallback
            // (same as the framework's implicit promise of "stream what we know about") —
            // upgraded log so the operator notices this wasn't the preferred path.
            log.info("No event-stream tables registered yet; creating publication '{}' FOR ALL TABLES " +
                             "as fallback (will require superuser). Restart after registering aggregates " +
                             "to instead get an explicit-list publication.",
                     publicationName);
            handle.execute("CREATE PUBLICATION " + quoteIdentifier(publicationName) + " FOR ALL TABLES");
            return;
        }
        String tableListSql = registeredTables.stream()
                                              .filter(t -> t != null && !t.isBlank())
                                              .map(PgOutputLogicalDecodingPlugin::quoteTableName)
                                              .collect(Collectors.joining(", "));
        handle.execute("CREATE PUBLICATION " + quoteIdentifier(publicationName) + " FOR TABLE " + tableListSql);
        log.info("Created pgoutput publication '{}' FOR TABLE ({}) (auto-manage)", publicationName, tableListSql);
    }

    private void addTablesToPublication(Handle handle, String publicationName, Set<String> missingTables) {
        String tableListSql = missingTables.stream()
                                           .map(PgOutputLogicalDecodingPlugin::quoteTableName)
                                           .collect(Collectors.joining(", "));
        handle.execute("ALTER PUBLICATION " + quoteIdentifier(publicationName) + " ADD TABLE " + tableListSql);
        log.info("Added {} table(s) to pgoutput publication '{}' (auto-manage): {}",
                 missingTables.size(), publicationName, missingTables);
    }

    /**
     * Compute which registered tables are not covered by the publication's current members.
     * Matching is loose: a registered bare table name is considered covered by a fully-
     * qualified {@code schema.table} member with the same table-portion.
     */
    private static Set<String> diffMissing(Set<String> publicationMembers, Set<String> registered) {
        var missing = new TreeSet<String>();
        for (String table : registered) {
            if (table == null || table.isBlank()) continue;
            boolean covered = publicationMembers.stream().anyMatch(member -> {
                if (member.equalsIgnoreCase(table)) return true;
                int dot = member.indexOf('.');
                return dot > 0 && member.substring(dot + 1).equalsIgnoreCase(table);
            });
            if (!covered) missing.add(table);
        }
        return missing;
    }

    /**
     * Defensive quoting for an identifier — validates the name conforms to Postgres's rules
     * (letters/digits/underscore, start with letter or underscore, length bound) then wraps in
     * double quotes for the SQL. Rejects anything suspicious; auto-manage must never be a
     * SQL-injection vector.
     */
    private static String quoteIdentifier(String name) {
        PostgresqlUtil.checkIsValidTableOrColumnName(name);
        return "\"" + name + "\"";
    }

    /**
     * Quote a table reference that may be bare ({@code orders_events}) or schema-qualified
     * ({@code public.orders_events}). Each component is validated and double-quoted
     * independently so {@code CREATE PUBLICATION FOR TABLE "public"."orders_events"} is what
     * reaches the server.
     */
    private static String quoteTableName(String tableRef) {
        int dot = tableRef.indexOf('.');
        if (dot < 0) return quoteIdentifier(tableRef);
        return quoteIdentifier(tableRef.substring(0, dot)) + "." + quoteIdentifier(tableRef.substring(dot + 1));
    }

    /**
     * pgoutput RELATION messages are <b>all reported at LSN {@code 0/0}</b> by
     * {@code PGReplicationStream#getLastReceiveLSN()} — unlike row-change messages, they are
     * synthesized by the walsender rather than read from a WAL record, so they carry no WAL
     * position of their own.
     * <p>
     * That breaks the inbox's {@code unique(slot_name, lsn)} dedup key outright: the first
     * RELATION message to arrive claims {@code 0/0} and every subsequent one — i.e. the schema
     * for every <em>other</em> event-stream table — is swallowed by {@code on conflict do nothing}.
     * The dispatcher's decoder then knows exactly one relation, and every INSERT on the remaining
     * tables fails with {@link MissingRelationMetadataException} and is quarantined. With five
     * event-stream tables that is four tables' worth of CDC silently dead from the first event on.
     * <p>
     * Qualifying the key with the relation OID keeps each table's schema on its own row while
     * staying deterministic, so a RELATION message re-streamed after a reconnect still dedups
     * against the row it wrote the first time.
     */
    @Override
    public String inboxDedupKey(byte[] payloadBytes, String lsn) {
        if (payloadBytes == null || payloadBytes.length < 5 || (char) payloadBytes[0] != 'R') {
            return lsn;
        }
        int relationId = ((payloadBytes[1] & 0xFF) << 24)
                | ((payloadBytes[2] & 0xFF) << 16)
                | ((payloadBytes[3] & 0xFF) << 8)
                | (payloadBytes[4] & 0xFF);
        return lsn + ":R:" + relationId;
    }

    /**
     * {@code 'R'} (RELATION) is pgoutput's schema message — the decoder cannot turn any INSERT
     * into a row change until it has cached the matching relation.
     */
    @Override
    public Set<Integer> schemaPayloadLeadingBytes() {
        return Set.of((int) 'R');
    }

    /**
     * pgoutput payloads are binary, but a cheap binary peek at each message's type marker +
     * relation-id is plenty to decide whether the row belongs to a tracked event-stream table.
     * The dedicated {@code PgOutputRawPayloadFilter} implements that peek; wiring it via the
     * existing {@code preFiltersRawPayloads} gate lets the tailer drop irrelevant WAL messages
     * before they hit the inbox — removing the chatty B/C envelopes, all U/D/T/other types,
     * and all 'I's on non-event-stream tables that FOR-ALL-TABLES publications emit.
     */
    @Override
    public boolean preFiltersRawPayloads() {
        return true;
    }

    /**
     * pgoutput's raw payloads are binary; the dedicated {@link PgOutputRawPayloadFilter}
     * does a cheap header peek to drop irrelevant messages before they hit the inbox.
     * Wired here as the plugin-supplied default so callers that pass {@code Optional.empty()}
     * to the tailer get the right filter instead of the generic last-resort fallback.
     */
    @Override
    public Optional<WalMessageFilter> defaultRawPayloadFilter(Supplier<Set<String>> eventStreamTableNamesSupplier) {
        requireNonNull(eventStreamTableNamesSupplier, "eventStreamTableNamesSupplier cannot be null");
        // PgOutputRawPayloadFilter takes Supplier<Collection<String>>; Set is a Collection so
        // we just adapt with a lambda.
        return Optional.of(new PgOutputRawPayloadFilter(eventStreamTableNamesSupplier::get));
    }

    @Override
    public DiagnosticSummary diagnosticSummary() {
        // Render a compact histogram of pgoutput message types so failures like "zero INSERTs
        // arriving" show up plainly. Format: "types={B=123, C=123, R=5, I=0, Y=42}"
        var counts = rowChangeDecoder.messageTypeCountsSnapshot();
        String extra = counts.isEmpty()
                       ? null
                       : "types=" + counts;
        return new DiagnosticSummary(
                converter.getInsertsSeenCount(),
                converter.getInsertsWithUnknownAggregateCount(),
                extra);
    }

    private List<PgOutputRowChange> decodeRowChanges(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) return List.of();
        var decodedMessage = messageDecoder.decode(payloadBytes);
        return rowChangeDecoder.accept(decodedMessage);
    }

    public int protocolVersion() {
        return properties.getProtoVersion();
    }
}
