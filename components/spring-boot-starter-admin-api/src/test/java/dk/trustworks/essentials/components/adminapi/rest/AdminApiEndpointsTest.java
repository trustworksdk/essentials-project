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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end behaviour of the HTTP layer: routing, parameter binding and defaults, JSON bodies, and the mapping of
 * SPI outcomes onto the statuses the contract declares.
 * <p>
 * The SPIs are mocked, so no database is involved — including the authorization decision, which the real SPI
 * implementations delegate to {@link EssentialsSecurityProvider}. Here that decision is simulated by having the mock
 * throw {@link EssentialsSecurityException}, exactly as the real SPI does.
 */
class AdminApiEndpointsTest {

    private static final String BASE = AdminApiPaths.DEFAULT_BASE_PATH;

    private final DBFencedLockApi  dbFencedLockApi  = mock(DBFencedLockApi.class);
    private final DurableQueuesApi durableQueuesApi = mock(DurableQueuesApi.class);
    private final AggregateLifecycleApi aggregateLifecycleApi = mock(AggregateLifecycleApi.class);
    private final AggregateArchiveApi   aggregateArchiveApi   = mock(AggregateArchiveApi.class);

    private final TestAuthenticatedUser authenticatedUser = new TestAuthenticatedUser();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var principalResolver = new AdminApiPrincipalResolver(authenticatedUser);
        var jsonMapper        = JsonMapper.builder().addModule(new AdminApiJacksonModule()).build();

        mockMvc = MockMvcBuilders.standaloneSetup(new FencedLocksController(dbFencedLockApi, principalResolver),
                                                 new DurableQueuesController(durableQueuesApi, principalResolver),
                                                 new AggregateLifecycleController(aggregateLifecycleApi, principalResolver),
                                                 new AggregateArchiveController(aggregateArchiveApi, principalResolver))
                                 .setControllerAdvice(new AdminApiExceptionHandler())
                                 .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                                 .addPlaceholderValue(AdminApiPaths.BASE_PATH_PROPERTY, BASE)
                                 .build();
    }

    @Nested
    class WhenTheCallerIsAuthorized {

        @Test
        void a_list_operation_returns_the_spi_result_with_value_types_as_primitives() throws Exception {
            when(dbFencedLockApi.getAllLocks(any()))
                    .thenReturn(List.of(new ApiDBFencedLock(LockName.of("my-lock"),
                                                            17L,
                                                            "instance-1",
                                                            OffsetDateTime.parse("2026-07-31T10:15:30Z"),
                                                            OffsetDateTime.parse("2026-07-31T10:16:30Z"))));

            mockMvc.perform(get(BASE + "/fenced-locks"))
                   .andExpect(status().isOk())
                   .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                   .andExpect(jsonPath("$[0].lockName").value("my-lock"))
                   .andExpect(jsonPath("$[0].currentToken").value(17));
        }

        @Test
        void a_boolean_spi_result_is_wrapped_in_the_contract_envelope() throws Exception {
            when(dbFencedLockApi.releaseLock(any(), eq(LockName.of("my-lock")))).thenReturn(true);

            mockMvc.perform(delete(BASE + "/fenced-locks/my-lock"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.released").value(true));
        }

        @Test
        void a_count_operation_is_wrapped_in_the_contract_envelope() throws Exception {
            when(durableQueuesApi.getTotalMessagesQueuedFor(any(), eq(QueueName.of("orders")))).thenReturn(42L);

            mockMvc.perform(get(BASE + "/durable-queues/queues/orders/messages/count"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.total").value(42));
        }

        @Test
        void pagination_and_sort_order_fall_back_to_the_contract_defaults() throws Exception {
            when(durableQueuesApi.getQueuedMessages(any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/durable-queues/queues/orders/messages"))
                   .andExpect(status().isOk());

            verify(durableQueuesApi).getQueuedMessages(any(), eq(QueueName.of("orders")), eq(QueueingSortOrder.ASC), eq(0L), eq(100L));
        }

        @Test
        void explicit_pagination_and_sort_order_are_passed_through() throws Exception {
            when(durableQueuesApi.getDeadLetterMessages(any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/durable-queues/queues/orders/dead-letter-messages")
                                    .param("sortOrder", "DESC")
                                    .param("startIndex", "20")
                                    .param("pageSize", "5"))
                   .andExpect(status().isOk());

            verify(durableQueuesApi).getDeadLetterMessages(any(), eq(QueueName.of("orders")), eq(QueueingSortOrder.DESC), eq(20L), eq(5L));
        }

        @Test
        void a_request_body_is_bound_and_its_delay_passed_to_the_spi() throws Exception {
            when(durableQueuesApi.resurrectDeadLetterMessage(any(), any(), any()))
                    .thenReturn(Optional.of(queuedMessage()));

            mockMvc.perform(post(BASE + "/durable-queues/messages/entry-1/resurrect")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"deliveryDelay\":\"PT30S\"}"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$.id").value("entry-1"));

            verify(durableQueuesApi).resurrectDeadLetterMessage(any(), eq(QueueEntryId.of("entry-1")), eq(Duration.ofSeconds(30)));
        }

        @Test
        void an_empty_optional_becomes_404_with_the_contract_error_body() throws Exception {
            when(durableQueuesApi.getQueuedMessage(any(), any())).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/durable-queues/messages/unknown-entry"))
                   .andExpect(status().isNotFound())
                   .andExpect(jsonPath("$.status").value(404))
                   .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        void an_unparseable_path_variable_becomes_400() throws Exception {
            mockMvc.perform(get(BASE + "/durable-queues/queues/orders/messages").param("sortOrder", "SIDEWAYS"))
                   .andExpect(status().isBadRequest())
                   .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    class WhenSecurityRejectsTheCaller {

        @Test
        void an_unauthenticated_caller_gets_401_and_the_spi_is_never_called() throws Exception {
            authenticatedUser.authenticated = false;

            mockMvc.perform(get(BASE + "/fenced-locks"))
                   .andExpect(status().isUnauthorized())
                   .andExpect(jsonPath("$.status").value(401))
                   .andExpect(jsonPath("$.error").value("Unauthorized"));

            verifyNoInteractions(dbFencedLockApi);
        }

        @Test
        void a_caller_missing_the_required_role_gets_403() throws Exception {
            when(dbFencedLockApi.getAllLocks(any()))
                    .thenThrow(new EssentialsSecurityException("Unauthorized access required role is missing"));

            mockMvc.perform(get(BASE + "/fenced-locks"))
                   .andExpect(status().isForbidden())
                   .andExpect(jsonPath("$.status").value(403))
                   .andExpect(jsonPath("$.message").value("Unauthorized access required role is missing"));
        }

        @Test
        void an_unexpected_failure_becomes_500_without_leaking_the_cause() throws Exception {
            when(dbFencedLockApi.getAllLocks(any()))
                    .thenThrow(new IllegalStateException("connection to db-host-7 refused: password authentication failed"));

            mockMvc.perform(get(BASE + "/fenced-locks"))
                   .andExpect(status().isInternalServerError())
                   .andExpect(jsonPath("$.status").value(500))
                   .andExpect(jsonPath("$.message").doesNotExist());
        }
    }

    private static ApiQueuedMessage queuedMessage() {
        return new ApiQueuedMessage(QueueEntryId.of("entry-1"),
                                    QueueName.of("orders"),
                                    null,
                                    OffsetDateTime.parse("2026-07-31T10:15:30Z"),
                                    null,
                                    null,
                                    null,
                                    1,
                                    0,
                                    false,
                                    false);
    }

    /** Stands in for the consumer's own {@link EssentialsAuthenticatedUser} implementation. */
    private static final class TestAuthenticatedUser implements EssentialsAuthenticatedUser {

        private boolean authenticated = true;

        @Override
        public Object getPrincipal() {
            return "test-principal";
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public void logout() {
        }
    }

    /**
     * The aggregate lifecycle and archive endpoints. Their contract-visible behaviour beyond plain delegation is the
     * defaulted {@code includeSnapshotPayload} query parameter and the mapping of an empty {@link Optional} onto 404.
     */
    @Nested
    class AggregateLifecycleAndArchiveEndpoints {

        @Test
        void include_snapshot_payload_defaults_to_false() throws Exception {
            when(aggregateLifecycleApi.findSnapshots(any(), any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/aggregate-lifecycle/aggregate-types/Orders/aggregates/order-1/snapshots"))
                   .andExpect(status().isOk());

            verify(aggregateLifecycleApi).findSnapshots(any(), eq(AggregateType.of("Orders")), eq("order-1"), eq(false));
        }

        @Test
        void include_snapshot_payload_is_passed_through_when_requested() throws Exception {
            when(aggregateLifecycleApi.findSnapshots(any(), any(), any(), anyBoolean())).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/aggregate-lifecycle/aggregate-types/Orders/aggregates/order-1/snapshots")
                                    .param("includeSnapshotPayload", "true"))
                   .andExpect(status().isOk());

            verify(aggregateLifecycleApi).findSnapshots(any(), eq(AggregateType.of("Orders")), eq("order-1"), eq(true));
        }

        @Test
        void no_open_generation_becomes_404_with_the_contract_error_body() throws Exception {
            when(aggregateLifecycleApi.findCurrentClosingBooksGeneration(any(), any(), any())).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/aggregate-lifecycle/aggregate-types/Orders/logical-aggregates/order-1/closing-books-generations/current"))
                   .andExpect(status().isNotFound())
                   .andExpect(jsonPath("$.status").value(404))
                   .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        void no_generation_event_stream_becomes_404() throws Exception {
            when(aggregateLifecycleApi.findClosingBooksGenerationEventStream(any(), any(), any(), anyLong())).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/aggregate-lifecycle/aggregate-types/Orders/logical-aggregates/order-1/closing-books-generations/3/event-stream"))
                   .andExpect(status().isNotFound());
        }

        @Test
        void no_archived_generation_becomes_404() throws Exception {
            when(aggregateArchiveApi.findArchivedGeneration(any(), any(), any(), anyLong())).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/aggregate-archive/aggregate-types/Orders/logical-aggregates/order-1/archived-generations/3"))
                   .andExpect(status().isNotFound());
        }

        @Test
        void a_non_numeric_generation_becomes_400() throws Exception {
            mockMvc.perform(get(BASE + "/aggregate-archive/aggregate-types/Orders/logical-aggregates/order-1/archived-generations/not-a-number"))
                   .andExpect(status().isBadRequest());
        }
    }
}
