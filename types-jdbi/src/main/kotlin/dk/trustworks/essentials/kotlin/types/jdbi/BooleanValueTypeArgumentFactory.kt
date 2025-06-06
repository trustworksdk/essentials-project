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

package dk.trustworks.essentials.kotlin.types.jdbi

import dk.trustworks.essentials.kotlin.types.BooleanValueType
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.statement.StatementContext
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Base implementation for a value class that implements [BooleanValueType]<br>
 *
 * @param <T> the concrete [BooleanValueType] value class
 */
abstract class BooleanValueTypeArgumentFactory<T : BooleanValueType<T>> : AbstractArgumentFactory<T>(Types.BOOLEAN) {
    override fun build(value: T, config: ConfigRegistry?): Argument {
        return Argument { position: Int, statement: PreparedStatement, ctx: StatementContext? ->
            statement.setBoolean(
                position,
                value.value
            )
        }
    }
}