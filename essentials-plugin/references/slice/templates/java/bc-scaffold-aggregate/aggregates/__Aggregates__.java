package {{packagePath}}.{{bc}}.aggregates;

import {{packagePath}}.{{bc}}.events.{{Aggregate}}Event;
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link {{Aggregate}}} aggregates, and the owner of this BC's {@link AggregateType}
 * — the name its events are stored under, which every subscriber and projection refers back to.
 *
 * IT WRAPS {@link StatefulAggregateRepository} RATHER THAN EXPOSING IT. The wrapper exists so the
 * bounded context speaks its own language ({@code get{{Aggregate}}}, {@code has{{Aggregate}}}) instead of a
 * generic {@code load}/{@code save}, and so the surface stays small enough to reason about. Do not
 * make the underlying repository a bean and inject it into slices directly — the whole BC's write
 * access should go through this one type.
 *
 * IT DOES NOT CONSTRUCT AGGREGATES. Constructing one is what emits the creation event, and that is a
 * *decision* — it belongs to the slice that took it, not to persistence. {@link #saveNew} persists an
 * instance the slice has already built.
 *
 * This class is the BC's only write path, so it is also the natural place for the cheap existence
 * check every creation slice needs for idempotency (§R5, aggregate style).
 */
@Component
public class {{Aggregates}} {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("{{AggregateType}}");

    private final StatefulAggregateRepository<{{Aggregate}}Id, {{Aggregate}}Event, {{Aggregate}}> repository;

    public {{Aggregates}}(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        this.repository = StatefulAggregateRepository.from(eventStore,
                                                           AGGREGATE_TYPE,
                                                           reflectionBasedAggregateRootFactory(),
                                                           {{Aggregate}}.class);
    }

    /**
     * Loads and rehydrates by replaying the stream. Throws when the aggregate does not exist.
     */
    public {{Aggregate}} get{{Aggregate}}({{Aggregate}}Id {{aggregate}}Id) {
        requireNonNull({{aggregate}}Id, "No {{aggregate}}Id provided");
        return repository.load({{aggregate}}Id);
    }

    public Optional<{{Aggregate}}> find{{Aggregate}}({{Aggregate}}Id {{aggregate}}Id) {
        requireNonNull({{aggregate}}Id, "No {{aggregate}}Id provided");
        return repository.tryLoad({{aggregate}}Id);
    }

    /**
     * Existence check for the idempotency guard in a creation slice.
     *
     * NOTE this rehydrates the aggregate. When a BC's streams grow long, replace it with a first-event
     * probe against the EventStore — {@code eventStore.fetchStream(AGGREGATE_TYPE, id,
     * LongRange.only(EventOrder.FIRST_EVENT_ORDER.longValue())).isPresent()} — which answers the same
     * question without replaying history. Keep the method name; only the body changes.
     */
    public boolean has{{Aggregate}}({{Aggregate}}Id {{aggregate}}Id) {
        return find{{Aggregate}}({{aggregate}}Id).isPresent();
    }

    public boolean is{{Aggregate}}Missing({{Aggregate}}Id {{aggregate}}Id) {
        return !has{{Aggregate}}({{aggregate}}Id);
    }

    /**
     * Persists an already-constructed aggregate. Constructing it — which is what emits the creation
     * event — is the owning slice's decision, so it happens there, not here.
     */
    public {{Aggregate}} saveNew({{Aggregate}} {{aggregate}}) {
        requireNonNull({{aggregate}}, "No {{aggregate}} provided");
        return repository.save({{aggregate}});
    }
}
