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
package dk.trustworks.essentials.components.foundation.schema;

import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Collects the schema every {@link EssentialsSchemaContributor} describes, puts it in order, and hands it to one
 * {@link SchemaApplier}.
 * <p>
 * Ordering is by {@link EssentialsSchemaContributor#order()}, then {@link EssentialsSchemaContributor#moduleId()}, so it
 * never depends on bean-construction order; contributors that tie keep their registration order. Several contributors
 * may share a module id - two durable-queue tables in one database are two instances of one module - but before the
 * applier sees anything the harness rejects two changes with the same {@code (module, changeId, objectName)}, which
 * would make the ledger ambiguous.
 */
public final class EssentialsSchemaHarness {
    private static final Logger log = LoggerFactory.getLogger(EssentialsSchemaHarness.class);

    private static final Comparator<EssentialsSchemaContributor> ORDERING =
            Comparator.comparingInt(EssentialsSchemaContributor::order)
                      .thenComparing(EssentialsSchemaContributor::moduleId);

    private final SchemaApplier                     applier;
    private final SchemaContext                     context;
    private final List<EssentialsSchemaContributor> contributors;

    /**
     * @param applier      what happens to the collected schema
     * @param context      what contributors are handed
     * @param contributors the contributors, in any order
     */
    public EssentialsSchemaHarness(SchemaApplier applier,
                                   SchemaContext context,
                                   List<? extends EssentialsSchemaContributor> contributors) {
        this.applier = requireNonNull(applier, "No applier provided");
        this.context = requireNonNull(context, "No context provided");
        this.contributors = List.copyOf(requireNonNull(contributors, "No contributors provided"));
    }

    /**
     * @return the ordered change sets that {@link #apply()} hands to the applier - without applying them
     */
    public List<SchemaChangeSet> collect() {
        var ordered = new ArrayList<EssentialsSchemaContributor>(contributors);
        ordered.sort(ORDERING);
        var identities = new HashSet<String>();
        var changeSets = new ArrayList<SchemaChangeSet>(ordered.size());
        for (var contributor : ordered) {
            var moduleId = requireNonNull(contributor.moduleId(), "Contributor {} has no moduleId", contributor.getClass().getName());
            var changes = requireNonNull(contributor.contribute(context), "Contributor '{}' returned no change list", moduleId);
            for (var change : changes) {
                requireNonNull(change, "Contributor '{}' returned a null change", moduleId);
                if (!identities.add(moduleId + '\u0000' + change.changeId() + '\u0000' + change.objectName())) {
                    throw new IllegalStateException(msg("Module '{}' change '{}' is contributed twice for object '{}' - by {}", moduleId, change.changeId(), change.objectName(),
                                                        contributor.getClass().getName()));
                }
            }
            changeSets.add(new SchemaChangeSet(moduleId, contributor.order(), changes));
        }
        return changeSets;
    }

    /**
     * Collect every contribution and hand the result to the applier. Every {@link DynamicSchemaContributor} is first
     * attached to a sink of the applier, so the objects it registers later are applied the same way.
     *
     * @throws RuntimeException whatever the applier or a contributor throws - a startup failure
     */
    public void apply() {
        // Before collecting, so an object registered while the sweep runs is not missed: it reaches the applier
        // through the sink, and possibly through the sweep as well, which a dynamic contributor's changes tolerate
        contributors.stream()
                    .filter(DynamicSchemaContributor.class::isInstance)
                    .map(DynamicSchemaContributor.class::cast)
                    .forEach(contributor -> contributor.attach(applier.sinkFor(contributor)));
        var changeSets = collect();
        log.info("Applying the schema of {} contributor(s) with {}: {}",
                 changeSets.size(),
                 applier.getClass().getSimpleName(),
                 changeSets.stream().map(SchemaChangeSet::moduleId).toList());
        applier.apply(changeSets);
    }
}
