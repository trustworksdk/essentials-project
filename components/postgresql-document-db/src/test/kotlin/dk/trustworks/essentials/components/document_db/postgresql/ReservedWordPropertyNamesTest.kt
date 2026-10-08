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
import dk.trustworks.essentials.components.document_db.Index
import dk.trustworks.essentials.components.document_db.Version
import dk.trustworks.essentials.components.document_db.VersionedEntity
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity
import dk.trustworks.essentials.components.document_db.annotations.Id
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.postgresql.InvalidTableOrColumnNameException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset.UTC
import java.util.Optional
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

@DocumentEntity("jdk_typed_documents")
data class JdkTypedDocument(
    @Id val id: String,
    var homepage: URI,
    var nickname: Optional<String>,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<String, JdkTypedDocument>

@DocumentEntity("order")
data class ReservedWordTableDocument(
    @Id val id: String,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<String, ReservedWordTableDocument>

@DocumentEntity("weird_documents")
data class WeirdPropertyNameDocument(
    @Id val id: String,
    val `weird name`: String,
    override var version: Version = Version.NOT_SAVED_YET,
    override var lastUpdated: OffsetDateTime = OffsetDateTime.now(UTC)
) : VersionedEntity<String, WeirdPropertyNameDocument>

/**
 * DD-2: entity property names and JSON path segments are checked for the identifier character class and length only.
 * The SQL reserved-keyword list applies to names PostgreSQL parses as identifiers - the table name - and nowhere else.
 */
@Suppress("UNCHECKED_CAST")
class ReservedWordPropertyNamesTest {

    @Test
    fun `an entity whose property names are SQL reserved words can be configured`() {
        val configuration = EntityConfiguration.configureEntity<ReservedWordsDocument, String>(ReservedWordsDocument::class)

        assertThat(configuration.indexedFields().map { it.name }).containsExactly("order")
    }

    @Test
    fun `properties of JDK types are checked by name but not recursed into`() {
        val checkedContexts = mutableListOf<String>()
        JdkTypedDocument::class.memberProperties.forEach {
            EntityConfiguration.checkPropertyNames(it as KProperty1<Any, *>, listOf("JdkTypedDocument"), mutableSetOf()) { _, context -> checkedContexts.add(context) }
        }

        assertThat(checkedContexts).contains("JdkTypedDocument.homepage", "JdkTypedDocument.nickname")
        // URI.path and Optional.value used to be checked - and rejected as reserved keywords
        assertThat(checkedContexts).noneMatch { it.startsWith("JdkTypedDocument.homepage.") || it.startsWith("JdkTypedDocument.nickname.") }
        assertThatCode { EntityConfiguration.configureEntity<JdkTypedDocument, String>(JdkTypedDocument::class) }.doesNotThrowAnyException()
    }

    @Test
    fun `a reserved word reaches the SQL only as a quoted JSON path segment and inside a bind name`() {
        val configuration = EntityConfiguration.configureEntity<ReservedWordsDocument, String>(ReservedWordsDocument::class)
        val repository = mock(DocumentDbRepository::class.java) as DocumentDbRepository<ReservedWordsDocument, String>

        val query = QueryBuilder(configuration, repository)
            .where(Condition<ReservedWordsDocument>(mock(JSONSerializer::class.java)).matching {
                ReservedWordsDocument::group then ReservedWordsGroup::select gt 1
            })
            .orderBy(ReservedWordsDocument::order, QueryBuilder.Order.ASC)
            .orderBy("group.table", QueryBuilder.Order.DESC)
            .build()

        assertThat(query.sql).isEqualTo(
            "SELECT data FROM reserved_word_documents WHERE CAST(data->'group'->>'select' AS INTEGER) > :group_select__0" +
                " ORDER BY CAST(data->>'order' AS TEXT) ASC, data->'group'->>'table' DESC"
        )
        assertThat(query.bindings).containsEntry("group_select__0", 1)
    }

    @Test
    fun `the Java path API accepts reserved words as path segments`() {
        assertThat(JsonPathProperty<ReservedWordsDocument>("group.table").toJSONValueArrowPath()).isEqualTo("data->'group'->>'table'")
        assertThat(Index.fromPaths<ReservedWordsDocument>("by_user", "user", "group.select").properties).hasSize(2)
        assertThat(checkJsonPathExpression("data->'select'->>'from'")).isEqualTo("data->'select'->>'from'")
    }

    @Test
    fun `a table name that is a reserved word is still rejected, and the message says so`() {
        assertThatThrownBy { EntityConfiguration.configureEntity<ReservedWordTableDocument, String>(ReservedWordTableDocument::class) }
            .isInstanceOf(InvalidTableOrColumnNameException::class.java)
            .hasMessageContaining("'order'")
            .hasMessageContaining("reserved SQL keyword")
    }

    @Test
    fun `a property name outside the identifier character class is still rejected, naming its context`() {
        assertThatThrownBy { EntityConfiguration.configureEntity<WeirdPropertyNameDocument, String>(WeirdPropertyNameDocument::class) }
            .isInstanceOf(InvalidTableOrColumnNameException::class.java)
            .hasMessageContaining("Invalid JSON property name: 'weird name'")
            .hasMessageContaining("WeirdPropertyNameDocument.weird name")
            .hasMessageNotContaining("reserved")
    }
}
