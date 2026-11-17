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

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.integration.api.*;
import dk.trustworks.essentials.shared.security.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.*;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link EventLogSubscriptionsController}: routing, the {@link ProgressKey} built from path and query (an omitted
 * {@code instanceName} is the exclusive row), parameter defaults, the wire shape of the new engine's DTOs (empty
 * optionals as {@code null}, values as primitives) and the contract statuses.
 */
class EventLogSubscriptionsEndpointsTest {
    private static final String BASE         = AdminApiPaths.DEFAULT_BASE_PATH;
    private static final String SUBSCRIPTION = BASE + "/event-log/subscriptions/orders-view/aggregate-types/Orders";

    private final EventLogSubscriptionsApi api           = mock(EventLogSubscriptionsApi.class);
    private       boolean                  authenticated = true;
    private       MockMvc                  mockMvc;

    @BeforeEach
    void setUp() {
        var user = new EssentialsAuthenticatedUser() {
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
        };
        var jsonMapper = JsonMapper.builder().addModule(new AdminApiJacksonModule()).build();
        mockMvc = MockMvcBuilders.standaloneSetup(new EventLogSubscriptionsController(api, new AdminApiPrincipalResolver(user)))
                                 .setControllerAdvice(new AdminApiExceptionHandler())
                                 .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                                 .addPlaceholderValue(AdminApiPaths.BASE_PATH_PROPERTY, BASE)
                                 .build();
    }

    private static ApiEventLogSubscription subscription(Optional<String> leaseOwner, OptionalLong lag) {
        return new ApiEventLogSubscription("orders-view", "Orders", "", true, "CONTIGUOUS", "WATERMARK", 42, 3, leaseOwner,
                                           leaseOwner.map(o -> OffsetDateTime.parse("2026-11-17T10:00:00Z")), leaseOwner.isPresent() ? OptionalLong.of(9) : OptionalLong.empty(),
                                           OffsetDateTime.parse("2026-11-17T09:00:00Z"), Optional.empty(), 1, 4, lag);
    }

    @Test
    void lists_every_subscription_or_one_aggregate_type() throws Exception {
        when(api.getSubscriptions(any(), any())).thenReturn(List.of(subscription(Optional.of("node-1/a"), OptionalLong.of(17))));

        mockMvc.perform(get(BASE + "/event-log/subscriptions"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].subscriber").value("orders-view"))
               .andExpect(jsonPath("$[0].instanceName").value(""))
               .andExpect(jsonPath("$[0].resumePoint").value(42))
               .andExpect(jsonPath("$[0].leaseOwner").value("node-1/a"))
               .andExpect(jsonPath("$[0].fence").value(9))
               .andExpect(jsonPath("$[0].lag").value(17))
               .andExpect(jsonPath("$[0].leaseUntil").isString())
               .andExpect(jsonPath("$[0].key").doesNotExist());
        verify(api).getSubscriptions(any(), eq(Optional.empty()));

        mockMvc.perform(get(BASE + "/event-log/subscriptions").param("aggregateType", "Orders")).andExpect(status().isOk());
        verify(api).getSubscriptions(any(), eq(Optional.of("Orders")));
    }

    @Test
    void empty_optionals_are_written_as_null() throws Exception {
        when(api.getSubscription(any(), any())).thenReturn(Optional.of(subscription(Optional.empty(), OptionalLong.empty())));

        mockMvc.perform(get(SUBSCRIPTION))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.leaseOwner").value(nullValue()))
               .andExpect(jsonPath("$.leaseUntil").value(nullValue()))
               .andExpect(jsonPath("$.fence").value(nullValue()))
               .andExpect(jsonPath("$.filterDigest").value(nullValue()))
               .andExpect(jsonPath("$.lag").value(nullValue()));
    }

    @Test
    void an_omitted_instance_name_addresses_the_exclusive_row() throws Exception {
        when(api.getSubscription(any(), any())).thenReturn(Optional.of(subscription(Optional.empty(), OptionalLong.empty())));

        mockMvc.perform(get(SUBSCRIPTION)).andExpect(status().isOk());
        verify(api).getSubscription(any(), eq(ProgressKey.exclusive("orders-view", "Orders")));

        mockMvc.perform(get(SUBSCRIPTION).param("instanceName", "pod-2")).andExpect(status().isOk());
        verify(api).getSubscription(any(), eq(new ProgressKey("orders-view", "Orders", "pod-2")));
    }

    @Test
    void an_unknown_subscription_is_404() throws Exception {
        when(api.getSubscription(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get(SUBSCRIPTION))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void parked_events_default_to_the_first_page_of_100() throws Exception {
        var head = new ApiParkedEventsPage.ApiParkedEvent(7, "order-1", OptionalLong.empty(), 3, Optional.of("java.lang.IllegalStateException"),
                                                          Optional.of("boom"), OffsetDateTime.parse("2026-11-17T08:00:00Z"), Optional.of("RETRY"),
                                                          Optional.of(OffsetDateTime.parse("2026-11-17T08:05:00Z")));
        var held = new ApiParkedEventsPage.ApiParkedEvent(8, "order-1", OptionalLong.of(7), 0, Optional.empty(), Optional.empty(),
                                                          OffsetDateTime.parse("2026-11-17T08:01:00Z"), Optional.empty(), Optional.empty());
        when(api.getParkedEvents(any(), any(), anyLong(), anyInt())).thenReturn(new ApiParkedEventsPage(List.of(head, held), OptionalLong.of(8)));

        mockMvc.perform(get(SUBSCRIPTION + "/parked-events"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.events[0].globalOrder").value(7))
               .andExpect(jsonPath("$.events[0].blockedBy").value(nullValue()))
               .andExpect(jsonPath("$.events[0].requested").value("RETRY"))
               .andExpect(jsonPath("$.events[1].blockedBy").value(7))
               .andExpect(jsonPath("$.events[1].errorType").value(nullValue()))
               .andExpect(jsonPath("$.nextAfter").value(8));
        verify(api).getParkedEvents(any(), eq(ProgressKey.exclusive("orders-view", "Orders")), eq(0L), eq(100));

        mockMvc.perform(get(SUBSCRIPTION + "/parked-events").param("instanceName", "pod-2").param("afterGlobalOrder", "8").param("limit", "5"))
               .andExpect(status().isOk());
        verify(api).getParkedEvents(any(), eq(new ProgressKey("orders-view", "Orders", "pod-2")), eq(8L), eq(5));
    }

    @Test
    void reset_requires_resume_from_and_reports_whether_it_happened() throws Exception {
        when(api.resetSubscription(any(), any(), anyLong())).thenReturn(true, false);

        mockMvc.perform(post(SUBSCRIPTION + "/reset").param("resumeFrom", "100"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.reset").value(true));
        verify(api).resetSubscription(any(), eq(ProgressKey.exclusive("orders-view", "Orders")), eq(100L));

        mockMvc.perform(post(SUBSCRIPTION + "/reset").param("resumeFrom", "100"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.reset").value(false));

        mockMvc.perform(post(SUBSCRIPTION + "/reset")).andExpect(status().isBadRequest());
    }

    @Test
    void the_spi_rejecting_a_parameter_is_400() throws Exception {
        when(api.resetSubscription(any(), any(), anyLong())).thenThrow(new IllegalArgumentException("resumeFrom must be >= 1"));

        mockMvc.perform(post(SUBSCRIPTION + "/reset").param("resumeFrom", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void retry_and_discard_return_the_request_result() throws Exception {
        when(api.retryParkedEvent(any(), any(), anyLong())).thenReturn(ParkedRequestResult.ACCEPTED);
        when(api.discardParkedEvent(any(), any(), anyLong())).thenReturn(ParkedRequestResult.NOT_A_HEAD);

        mockMvc.perform(post(SUBSCRIPTION + "/parked-events/7/retry").param("instanceName", "pod-2"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value("ACCEPTED"));
        verify(api).retryParkedEvent(any(), eq(new ProgressKey("orders-view", "Orders", "pod-2")), eq(7L));

        mockMvc.perform(post(SUBSCRIPTION + "/parked-events/7/discard"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value("NOT_A_HEAD"));
        verify(api).discardParkedEvent(any(), eq(ProgressKey.exclusive("orders-view", "Orders")), eq(7L));

        mockMvc.perform(post(SUBSCRIPTION + "/parked-events/seven/retry")).andExpect(status().isBadRequest());
    }

    @Test
    void delete_retired_instance_takes_the_instance_from_the_path() throws Exception {
        when(api.deleteRetiredInstance(any(), any())).thenReturn(true);

        mockMvc.perform(delete(SUBSCRIPTION + "/instances/pod-2"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.deleted").value(true));
        verify(api).deleteRetiredInstance(any(), eq(new ProgressKey("orders-view", "Orders", "pod-2")));
    }

    @Test
    void an_unauthenticated_caller_is_401() throws Exception {
        authenticated = false;

        mockMvc.perform(get(BASE + "/event-log/subscriptions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(api);
    }

    @Test
    void a_caller_without_the_role_is_403() throws Exception {
        when(api.resetSubscription(any(), any(), anyLong())).thenThrow(new EssentialsSecurityException("missing SUBSCRIPTION_WRITER"));

        mockMvc.perform(post(SUBSCRIPTION + "/reset").param("resumeFrom", "1")).andExpect(status().isForbidden());
    }

    @Test
    void a_database_failure_is_500_without_the_cause() throws Exception {
        when(api.getSubscriptions(any(), any())).thenThrow(new EventLogAdminException("Failed to list subscriptions", new java.sql.SQLException("db-host-7 refused")));

        mockMvc.perform(get(BASE + "/event-log/subscriptions"))
               .andExpect(status().isInternalServerError())
               .andExpect(jsonPath("$.message").doesNotExist());
    }
}
