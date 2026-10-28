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
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import dk.trustworks.essentials.components.document_db.Index
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * DD-2, end to end: property names that are SQL reserved words (`order`, `user`, `group`, `select`, `table`, ...) work for
 * the table DDL, the `@Indexed` index, an explicit [Index], conditions on top-level and nested properties, ordering and the
 * Java path API - because each reaches PostgreSQL only as a quoted JSON path segment, a JDBI bind name or a prefixed index name.
 */
@Testcontainers
class ReservedWordPropertyNamesIT {
    private lateinit var jdbi: Jdbi
    private lateinit var repository: DocumentDbRepository<ReservedWordsDocument, String>

    companion object {
        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer = PostgreSQLContainer("postgres:18.4")
            .apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
            }
    }

    @BeforeEach
    fun setup() {
        jdbi = Jdbi.create(postgresContainer.jdbcUrl, postgresContainer.username, postgresContainer.password)
        repository = DocumentDbRepositoryFactory(jdbi, JdbiUnitOfWorkFactory(jdbi), TestObjectMappers.createJSONSerializer())
            .createForStringId(ReservedWordsDocument::class)
        repository.addIndex(Index(name = "group_select", listOf(ReservedWordsDocument::group then ReservedWordsGroup::select)))
        repository.deleteAll()
    }

    @Test
    fun `an entity whose property names are SQL reserved words is stored, indexed and queried`() {
        repository.saveAll(document("d1", order = "o-2", select = 1), document("d2", order = "o-1", select = 2), document("d3", order = "o-3", select = 3))

        val byOrder = repository.queryBuilder()
            .where(repository.condition().matching { ReservedWordsDocument::order eq "o-1" })
            .find()
        assertThat(byOrder.map { it.id }).containsExactly("d2")

        val byNestedSelect = repository.queryBuilder()
            .where(repository.condition().matching { ReservedWordsDocument::group then ReservedWordsGroup::select gte 2 })
            .orderBy(ReservedWordsDocument::order, QueryBuilder.Order.DESC)
            .find()
        assertThat(byNestedSelect.map { it.id }).containsExactly("d3", "d2")

        val byJavaPath = repository.queryBuilder()
            .where(repository.condition().eq("user", "user-d1"))
            .orderBy("group.table", QueryBuilder.Order.ASC)
            .find()
        assertThat(byJavaPath.map { it.id }).containsExactly("d1")

        val indexNames = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'reserved_word_documents'").mapTo(String::class.java).list()
        }
        assertThat(indexNames).contains("idx_reserved_word_documents_order", "idx_reserved_word_documents_group_select")
    }

    private fun document(id: String, order: String, select: Int) = ReservedWordsDocument(
        id = id,
        order = order,
        user = "user-$id",
        values = mapOf("k" to "v"),
        key = "key-$id",
        path = "/a/b",
        language = "da",
        text = "some text",
        time = "12:00",
        group = ReservedWordsGroup(table = "table-$id", select = select)
    )
}
