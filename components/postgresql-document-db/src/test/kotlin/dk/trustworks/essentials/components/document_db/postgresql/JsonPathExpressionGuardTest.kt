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

package dk.trustworks.essentials.components.document_db.postgresql

import dk.trustworks.essentials.components.document_db.DocumentDbRepository
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.postgresql.InvalidTableOrColumnNameException
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * [Property] is `sealed`, but its implementations still wrap caller-authored text: [SingleProperty] and [NestedProperty]
 * take arbitrary [kotlin.reflect.KProperty1] references, and a Kotlin backtick-quoted identifier may contain quotes and
 * spaces. Validating only inside [JsonPathProperty] and [Index] would therefore leave the SQL concatenation itself
 * unguarded. These tests pin the guard at the sinks.
 */
class JsonPathExpressionGuardTest {

    /**
     * Property names a Kotlin author can legally write, but which would inject SQL if a [Property]'s emitted path
     * reached the statement unchecked.
     */
    private data class HostileIdentifiers(
        val `name'||(SELECT version())||'`: String = "",
        val `weird name`: String = "",
        val `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`: String = "",
        val `n' OR '1'='1'||(SELECT version())||'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`: String = ""
    )

    @Test
    fun `a plain JSON path expression is accepted`() {
        assertThat(checkJsonPathExpression("data->>'city'")).isEqualTo("data->>'city'")
        assertThat(checkJsonPathExpression("data->'contact'->>'city'")).isEqualTo("data->'contact'->>'city'")
        assertThat(checkJsonPathExpression("data->'contact'->'address'->>'city'")).isEqualTo("data->'contact'->'address'->>'city'")
        assertThat(checkJsonPathExpression("data->'contact'->'address'")).isEqualTo("data->'contact'->'address'")
    }

    @Test
    fun `every built-in Property implementation satisfies the guard`() {
        assertThat(checkJsonPathExpression(SingleProperty(Order::personName).toJSONValueArrowPath())).isNotNull()
        assertThat(checkJsonPathExpression(SingleProperty(Order::personName).toJSONArrowPath())).isNotNull()
        assertThat(checkJsonPathExpression(JsonPathProperty<Order>("contact.address.city").toJSONValueArrowPath())).isNotNull()
        assertThat(checkJsonPathExpression(JsonPathProperty<Order>("contact.address.city").toJSONArrowPath())).isNotNull()

        val nested = NestedProperty<Order, String>(Condition(mock(JSONSerializer::class.java)), listOf(Order::contactDetails, ContactDetails::address, Address::city))
        assertThat(checkJsonPathExpression(nested.toJSONValueArrowPath())).isNotNull()
        assertThat(checkJsonPathExpression(nested.toJSONArrowPath())).isNotNull()
    }

    @Test
    fun `an expression that is not a plain JSON path is rejected`() {
        listOf(
            "data->>'name') ; DROP TABLE orders; --",
            "data->>'name' OR 1=1",
            "(SELECT version())",
            "EXISTS (SELECT 1 FROM jsonb_array_elements_text(data->'tags') AS elem)",
            "data->>'name'::text",
            "id",
            "data"
        ).forEach { expression ->
            assertThatThrownBy { checkJsonPathExpression(expression) }
                .describedAs("expression '%s'", expression)
                .isInstanceOf(InvalidTableOrColumnNameException::class.java)
        }
    }

    @Test
    fun `a path segment that is not a valid identifier is rejected`() {
        listOf(
            "data->>''",
            "data->'a b'->>'c'",
            "data->>'name; DROP TABLE orders'",
            "data->'select'->>'c'"
        ).forEach { expression ->
            assertThatThrownBy { checkJsonPathExpression(expression) }
                .describedAs("expression '%s'", expression)
                .isInstanceOf(InvalidTableOrColumnNameException::class.java)
        }
    }

    @Test
    fun `a hostile Property is rejected when a Condition is applied`() {
        val condition = Condition<HostileIdentifiers>(mock(JSONSerializer::class.java))

        assertThatThrownBy {
            condition.applyCondition(SingleProperty(HostileIdentifiers::`name'||(SELECT version())||'`), "=", "value")
        }.isInstanceOf(InvalidTableOrColumnNameException::class.java)
    }

    /**
     * The guard constrains what a [Property] may *emit*, not what [Condition] may *compose* from it. The commented-out
     * `anyLike` builds its `EXISTS (…)` fragment in [Condition] around a plain [SingleProperty] path, so it is unaffected -
     * it only has to read the path through [checkedJSONValueArrowPath].
     */
    @Test
    fun `a fragment composed around a checked path is unaffected by the guard`() {
        val path = SingleProperty(ContactDetails::phoneNumbers).checkedJSONValueArrowPath()

        assertThat("EXISTS (SELECT 1 FROM jsonb_array_elements_text($path) AS elem WHERE elem LIKE :phoneNumbers__0)")
            .isEqualTo("EXISTS (SELECT 1 FROM jsonb_array_elements_text(data->>'phoneNumbers') AS elem WHERE elem LIKE :phoneNumbers__0)")
    }

    @Test
    fun `a built-in Property over a hostile Kotlin identifier is rejected`() {
        listOf(
            SingleProperty(HostileIdentifiers::`name'||(SELECT version())||'`),
            SingleProperty(HostileIdentifiers::`weird name`)
        ).forEach { property ->
            assertThatThrownBy { property.checkedJSONValueArrowPath() }
                .describedAs("property '%s'", property.name())
                .isInstanceOf(InvalidTableOrColumnNameException::class.java)
        }
    }

    /**
     * A bind name is not a PostgreSQL identifier, so [PostgresqlUtil.MAX_IDENTIFIER_LENGTH] is not the bound - a name that
     * would be far too long for a column is a perfectly ordinary [NestedProperty] chain.
     */
    @Test
    fun `a property name well past the identifier limit is accepted as a bind name`() {
        val condition = Condition<Order>(mock(JSONSerializer::class.java))
        val property = SingleProperty(HostileIdentifiers::`aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa`)

        assertThat(property.name()).hasSizeGreaterThan(PostgresqlUtil.MAX_IDENTIFIER_LENGTH)
        assertThat(condition.uniqueBindName(property)).isEqualTo(property.name() + "__0")
    }

    @Test
    fun `a bind name is truncated rather than rejected once it passes the bind-name bound`() {
        val condition = Condition<Order>(mock(JSONSerializer::class.java))
        // 5 segments of 60 characters each: every segment is a legal identifier, the joined name is not a legal bind name
        val property = JsonPathProperty<Order>((1..5).joinToString(".") { "segment$it".padEnd(60, 'x') })

        val bindName = condition.uniqueBindName(property)

        assertThat(property.name()).hasSize(304)
        assertThat(bindName).hasSize(256 + "__0".length).startsWith("segment1").endsWith("__0")
    }

    @Test
    fun `truncation does not let a hostile name through`() {
        val condition = Condition<Order>(mock(JSONSerializer::class.java))
        val property = SingleProperty(HostileIdentifiers::`n' OR '1'='1'||(SELECT version())||'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`)

        assertThatThrownBy { condition.uniqueBindName(property) }.isInstanceOf(InvalidTableOrColumnNameException::class.java)
    }

    @Test
    fun `a legitimate query still builds the expected SQL`() {
        val configuration = EntityConfiguration.configureEntity<Order, OrderId>(Order::class)

        @Suppress("UNCHECKED_CAST")
        val repository = mock(DocumentDbRepository::class.java) as DocumentDbRepository<Order, OrderId>

        val query = QueryBuilder(configuration, repository)
            .where(Condition<Order>(mock(JSONSerializer::class.java)).matching { Order::personName like "%John%" })
            .orderBy("contactDetails.address.city", DbType.TEXT, QueryBuilder.Order.ASC)
            .limit(10)
            .build()

        assertThat(query.sql).isEqualTo(
            "SELECT data FROM orders WHERE CAST(data->>'personName' AS TEXT) LIKE :personName__0" +
                " ORDER BY CAST(data->'contactDetails'->'address'->>'city' AS TEXT) ASC LIMIT :limit"
        )
        assertThat(query.bindings).containsEntry("personName__0", "%John%").containsEntry("limit", 10)
    }
}
