/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.jdbi;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.statement.*;

import java.util.Optional;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The EssentialsQueryTagger class is responsible for tagging SQL queries executed through
 * the provided Jdbi instance, ensuring all queries include a predefined comment tag
 * at the beginning. This allows for query tracking and identification in logging or databases.
 * <p>
 * The tagging is achieved by modifying the query template engine of the Jdbi instance,
 * appending the tag comment to each query before execution.
 * <p>
 * This class is typically used in scenarios where query identification and consistent
 * tagging are required for debugging, monitoring, or auditing purposes.
 * Use {@code EssentialsComponentsProperties#metrics#sql#enabled} to enable
 */
public class EssentialsQueryTagger {

    public static final String QUERY_TAG = "/* TAG: essentials */";
    public static final String QUERY_TAG_NL = "/* TAG: essentials */\n";

    private final Jdbi jdbi;

    public EssentialsQueryTagger(Jdbi jdbi) {
        this.jdbi = requireNonNull(jdbi, "jdbi cannot be null");
        tagQueries();
    }

    private void tagQueries() {
        var stmts = jdbi.getConfig(SqlStatements.class);
        var delegate = stmts.getTemplateEngine();

        stmts.setTemplateEngine(new TemplateEngine() {
            @Override
            public String render(String template, StatementContext ctx) {
                return QUERY_TAG_NL
                        + delegate.render(template, ctx);
            }

            @Override
            public Optional<Function<StatementContext,String>> parse(String template,
                                                                     ConfigRegistry config) {
                return delegate.parse(template, config)
                        .map(parser -> ctx ->
                                QUERY_TAG_NL
                                        + parser.apply(ctx));
            }
        });

    }
}
