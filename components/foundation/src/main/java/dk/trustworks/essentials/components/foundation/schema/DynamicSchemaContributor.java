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

/**
 * A contributor whose objects are not all known when the {@link EssentialsSchemaHarness} runs - for example one table
 * per {@code AggregateType}, registered whenever the application adds one.
 * <p>
 * {@link #contribute(SchemaContext)} describes every object registered so far, so the harness covers them in its
 * sweep like any other contributor's. Before that sweep the harness {@link #attach(SchemaChangeSink) attaches} a
 * sink, and from then on the contributor hands the changes of each newly registered object to it rather than
 * executing anything itself. An object registered while the sweep runs can therefore reach the applier twice, which
 * is why every change a dynamic contributor describes must be safe to repeat.
 */
public interface DynamicSchemaContributor extends EssentialsSchemaContributor {

    /**
     * Route the changes of every object registered from now on to {@code sink}. Replaces a previously attached sink.
     *
     * @param sink where the changes of a newly registered object go
     */
    void attach(SchemaChangeSink sink);
}
