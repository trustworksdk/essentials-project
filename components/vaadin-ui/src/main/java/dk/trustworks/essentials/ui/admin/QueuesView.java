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

package dk.trustworks.essentials.ui.admin;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.*;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.*;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.util.SecurityUtils;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder.ASC;

/**
 * QueuesView is a Vaadin-based UI component responsible for managing and displaying
 * queue-related data, including the messages in queues, their statistics, and controls
 * to search, filter, and refresh the displayed data. It integrates with the DurableQueuesApi
 * to fetch and manage queue information.
 * <p>
 * The component supports the following features:
 * - Dynamically updating UI elements based on the selected queue.
 * - Integration with filtering-based data providers for queue messages.
 * - Displaying queue statistics, including delivery timestamps, totals, and latencies.
 * - Responsive layout and visibility toggling for UI components based on the context.
 */
@UIScope
@PermitAll
@SpringComponent
@Route(value = "queues", layout = AdminMainLayout.class)
public class QueuesView extends VerticalLayout implements BeforeEnterObserver {

    private static final int TRUNCATE_PAYLOAD_AT_LENGTH = 25;

    private final DurableQueuesApi            durableQueuesApi;
    private final EssentialsAuthenticatedUser authenticatedUser;
    private final SecurityUtils               securityUtils;

    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ComboBox<QueueName> queueSelector        = new ComboBox<>("Select Queue");
    private final Span                totalQueuedLabel     = new Span();
    private final Span                totalDeadLetterLabel = new Span();
    private final Div                 statisticsPanel      = new Div();

    private final H3 queuedHeader     = new H3("Queued Messages");
    private final H3 deadLetterHeader = new H3("Dead Letter Messages");

    private final TextField queuedSearchField     = new TextField("Search Queued Messages");
    private final TextField deadLetterSearchField = new TextField("Search Dead Letter Messages");

    private final Button refreshButton = new Button("Refresh");

    private final Grid<ApiQueuedMessage> queuedMessagesGrid     = new Grid<>(ApiQueuedMessage.class);
    private final Grid<ApiQueuedMessage> deadLetterMessagesGrid = new Grid<>(ApiQueuedMessage.class);

    private ConfigurableFilterDataProvider<ApiQueuedMessage, Void, String> queuedProvider;
    private ConfigurableFilterDataProvider<ApiQueuedMessage, Void, String> deadLetterProvider;

    public QueuesView(DurableQueuesApi durableQueuesApi, EssentialsAuthenticatedUser authenticatedUser) {
        this.durableQueuesApi = durableQueuesApi;
        this.authenticatedUser = authenticatedUser;
        this.securityUtils = new SecurityUtils(authenticatedUser);

        setWidthFull();
        setDefaultHorizontalComponentAlignment(Alignment.START);
        setAlignItems(Alignment.START);
        getStyle().set("align-content", "flex-start");

        queueSelector.setWidth("100%");

        configureQueueSelector();
        configureGrids();

        queuedSearchField.setWidth("250px");
        deadLetterSearchField.setWidth("250px");

        refreshButton.addClickListener(click -> {
            queuedSearchField.clear();
            deadLetterSearchField.clear();
            queuedProvider.refreshAll();
            deadLetterProvider.refreshAll();
        });

        statisticsPanel.setVisible(false);
        queuedHeader.setVisible(false);
        queuedSearchField.setVisible(false);
        queuedMessagesGrid.setVisible(false);
        deadLetterHeader.setVisible(false);
        deadLetterSearchField.setVisible(false);
        deadLetterMessagesGrid.setVisible(false);
        refreshButton.setVisible(false);

        add(new H3("Queues administration"),
            queueSelector,
            statisticsPanel,
            refreshButton,
            queuedHeader,
            queuedSearchField,
            totalQueuedLabel,
            queuedMessagesGrid,
            deadLetterHeader,
            deadLetterSearchField,
            totalDeadLetterLabel,
            deadLetterMessagesGrid
           );

    }

    private void configureQueueSelector() {
        Set<QueueName> queues = durableQueuesApi.getQueueNames(authenticatedUser.getPrincipal());
        queueSelector.setItems(queues);
        queueSelector.addValueChangeListener(event -> {
            QueueName selectedQueue = event.getValue();
            updateQueueData(selectedQueue);
        });
    }

    private void updateQueueData(QueueName queueName) {
        if (queueName != null) {
            updateQueuedTotals(queueName);

            updateStatistics(queueName);

            setQueuedMessagesDataProvider(queueName);
            setDeadLetterMessagesDataProvider(queueName);

            statisticsPanel.setVisible(true);
            queuedHeader.setVisible(true);
            queuedSearchField.setVisible(true);
            queuedMessagesGrid.setVisible(true);
            deadLetterHeader.setVisible(true);
            deadLetterSearchField.setVisible(true);
            deadLetterMessagesGrid.setVisible(true);
            refreshButton.setVisible(true);
        } else {
            totalQueuedLabel.setText("");
            totalDeadLetterLabel.setText("");
            statisticsPanel.removeAll();
            queuedMessagesGrid.setItems();
            deadLetterMessagesGrid.setItems();
            queuedSearchField.clear();
            deadLetterSearchField.clear();
            statisticsPanel.setVisible(false);
            queuedHeader.setVisible(false);
            queuedSearchField.setVisible(false);
            queuedMessagesGrid.setVisible(false);
            deadLetterHeader.setVisible(false);
            deadLetterSearchField.setVisible(false);
            deadLetterMessagesGrid.setVisible(false);
            refreshButton.setVisible(false);
        }
    }

    private void updateQueuedTotals(QueueName queueName) {
        totalQueuedLabel.setText("Total Queued Messages: "
                                         + durableQueuesApi.getTotalMessagesQueuedFor(authenticatedUser.getPrincipal(), queueName));
        totalDeadLetterLabel.setText("Total Dead Letter Messages: "
                                             + durableQueuesApi.getTotalDeadLetterMessagesQueuedFor(authenticatedUser.getPrincipal(), queueName));
    }

    private void updateStatistics(QueueName queueName) {
        statisticsPanel.removeAll();
        Optional<ApiQueuedStatistics> stats = durableQueuesApi.getQueuedStatistics(authenticatedUser.getPrincipal(), queueName);
        if (stats.isPresent()) {
            FormLayout statsLayout = new FormLayout();
            statsLayout.addFormItem(new Span(formatDateTime(stats.get().fromTimestamp())), "From");
            statsLayout.addFormItem(new Span(String.valueOf(stats.get().totalMessagesDelivered())), "Total Delivered");
            statsLayout.addFormItem(new Span(String.valueOf(stats.get().avgDeliveryLatencyMs())), "Avg Latency (ms)");
            statsLayout.addFormItem(new Span(formatDateTime(stats.get().firstDelivery())), "First Delivery");
            statsLayout.addFormItem(new Span(formatDateTime(stats.get().lastDelivery())), "Last Delivery");
            statisticsPanel.add(statsLayout);
        } else {
            statisticsPanel.add(new Text("No statistics available."));
        }
    }

    private void setQueuedMessagesDataProvider(QueueName queueName) {
        queuedProvider = DataProvider.<ApiQueuedMessage, String>fromFilteringCallbacks(
                query -> {
                    String filter = query.getFilter().orElse(null); // Only QueueEntryId supported for now
                    int    offset = query.getOffset();
                    int    limit  = query.getLimit();
                    if (filter != null) {
                        return durableQueuesApi.getQueuedMessage(authenticatedUser.getPrincipal(), QueueEntryId.of(filter)).stream();
                    }
                    List<ApiQueuedMessage> messages = durableQueuesApi.getQueuedMessages(authenticatedUser.getPrincipal(), queueName, ASC, offset, limit);
                    return messages.stream();
                },
                query -> {
                    String filter = query.getFilter().orElse(null);
                    if (filter != null) {
                        return 1;
                    }
                    return (int) durableQueuesApi.getTotalMessagesQueuedFor(authenticatedUser.getPrincipal(), queueName);
                }
                                                                                      ).withConfigurableFilter();
        queuedMessagesGrid.setDataProvider(queuedProvider);

        queuedSearchField.addValueChangeListener(e -> {
            String searchTerm = e.getValue();
            queuedProvider.setFilter(searchTerm);
        });
    }

    private void setDeadLetterMessagesDataProvider(QueueName queueName) {
        deadLetterProvider = DataProvider.<ApiQueuedMessage, String>fromFilteringCallbacks(
                query -> {
                    String filter = query.getFilter().orElse(null); // Only QueueEntryId supported for now
                    int    offset = query.getOffset();
                    int    limit  = query.getLimit();
                    if (filter != null) {
                        return durableQueuesApi.getQueuedMessage(authenticatedUser.getPrincipal(), QueueEntryId.of(filter)).stream();
                    }
                    List<ApiQueuedMessage> messages = durableQueuesApi.getDeadLetterMessages(authenticatedUser.getPrincipal(), queueName, ASC, offset, limit);
                    return messages.stream();
                },
                query -> {
                    String filter = query.getFilter().orElse(null);
                    if (filter != null) {
                        return 1;
                    }
                    return (int) durableQueuesApi.getTotalDeadLetterMessagesQueuedFor(authenticatedUser.getPrincipal(), queueName);
                }
                                                                                          ).withConfigurableFilter();
        deadLetterMessagesGrid.setDataProvider(deadLetterProvider);

        deadLetterSearchField.addValueChangeListener(e -> {
            String searchTerm = e.getValue();
            deadLetterProvider.setFilter(searchTerm);
        });
    }

    private void configureGrids() {
        queuedMessagesGrid.removeAllColumns();
        queuedMessagesGrid.addColumn(ApiQueuedMessage::id)
                          .setHeader("Id")
                          .setAutoWidth(true)
                          .setTooltipGenerator(msg -> msg.id().toString());
        queuedMessagesGrid.addColumn(msg -> truncatePayload(msg.payload()))
                          .setHeader(createHeaderWithTooltip("Payload"))
                          .setAutoWidth(true)
                          .setTooltipGenerator(ApiQueuedMessage::payload);
        queuedMessagesGrid.addColumn(msg -> formatDateTime(msg.addedTimestamp()))
                          .setHeader(createHeaderWithTooltip("Added Timestamp"));
        queuedMessagesGrid.addColumn(msg -> formatDateTime(msg.nextDeliveryTimestamp()))
                          .setHeader("Next Delivery Timestamp")
                          .setAutoWidth(true);
        queuedMessagesGrid.addColumn(msg -> formatDateTime(msg.deliveryTimestamp()))
                          .setHeader(createHeaderWithTooltip("Delivery Timestamp"));
        queuedMessagesGrid.addColumn(msg -> truncatePayload(msg.lastDeliveryError()))
                          .setHeader(createHeaderWithTooltip("Last Delivery Error"))
                          .setAutoWidth(true)
                          .setTooltipGenerator(ApiQueuedMessage::lastDeliveryError);
        queuedMessagesGrid.addColumn(ApiQueuedMessage::totalDeliveryAttempts)
                          .setHeader(createHeaderWithTooltip("Total Attempts"));
        queuedMessagesGrid.addColumn(ApiQueuedMessage::redeliveryAttempts)
                          .setHeader(createHeaderWithTooltip("Redelivery Attempts"));
        queuedMessagesGrid.addColumn(ApiQueuedMessage::isBeingDelivered)
                          .setHeader(createHeaderWithTooltip("Being Delivered"));
        if (securityUtils.canWriteQueues()) {
            queuedMessagesGrid.addComponentColumn(msg -> new Button("Deadletter", click -> {
                                                      durableQueuesApi.markAsDeadLetterMessage(authenticatedUser.getPrincipal(), msg.id());
                                                      Notification.show("Mark as deadletter : " + msg.id());
                                                      queuedSearchField.clear();
                                                      queuedMessagesGrid.getDataProvider().refreshAll();
                                                      deadLetterMessagesGrid.getDataProvider().refreshAll();
                                                      updateQueuedTotals(msg.queueName());
                                                  })
                                                 ).setHeader("Actions");
            queuedMessagesGrid.addComponentColumn(msg -> new Button("Delete", click -> {
                                                      durableQueuesApi.deleteMessage(authenticatedUser.getPrincipal(), msg.id());
                                                      Notification.show("Deleted message : " + msg.id());
                                                      queuedSearchField.clear();
                                                      queuedMessagesGrid.getDataProvider().refreshAll();
                                                      deadLetterMessagesGrid.getDataProvider().refreshAll();
                                                      updateQueuedTotals(msg.queueName());
                                                  })
                                                 ).setHeader("Delete");
        }
        queuedMessagesGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        deadLetterMessagesGrid.removeAllColumns();
        deadLetterMessagesGrid.addColumn(ApiQueuedMessage::id)
                              .setHeader("Id")
                              .setTooltipGenerator(msg -> msg.id().toString());
        ;
        deadLetterMessagesGrid.addColumn(msg -> truncatePayload(msg.payload()))
                              .setHeader(createHeaderWithTooltip("Payload"))
                              .setAutoWidth(true)
                              .setTooltipGenerator(ApiQueuedMessage::payload);
        deadLetterMessagesGrid.addColumn(msg -> formatDateTime(msg.addedTimestamp()))
                              .setHeader(createHeaderWithTooltip("Added Timestamp"))
                              .setTooltipGenerator(msg -> formatDateTime(msg.addedTimestamp()));
        //deadLetterMessagesGrid.addColumn(createTruncatedTextRenderer(msg -> formatDateTime(msg.nextDeliveryTimestamp()))).setHeader(createHeaderWithTooltip("Next Delivery Timestamp"));
        deadLetterMessagesGrid.addColumn(msg -> formatDateTime(msg.deliveryTimestamp()))
                              .setHeader(createHeaderWithTooltip("Delivery Timestamp"));
        deadLetterMessagesGrid.addColumn(msg -> truncatePayload(msg.lastDeliveryError()))
                              .setHeader(createHeaderWithTooltip("Last Delivery Error"))
                              .setAutoWidth(true)
                              .setTooltipGenerator(ApiQueuedMessage::lastDeliveryError);
        deadLetterMessagesGrid.addColumn(ApiQueuedMessage::totalDeliveryAttempts)
                              .setHeader(createHeaderWithTooltip("Total Attempts"));
        deadLetterMessagesGrid.addColumn(ApiQueuedMessage::redeliveryAttempts)
                              .setHeader(createHeaderWithTooltip("Redelivery Attempts"));
        deadLetterMessagesGrid.addColumn(ApiQueuedMessage::isBeingDelivered)
                              .setHeader(createHeaderWithTooltip("Being Delivered"));
        if (securityUtils.canWriteQueues()) {
            deadLetterMessagesGrid.addComponentColumn(msg -> new Button("Resurrect", click -> {
                                                          durableQueuesApi.resurrectDeadLetterMessage(authenticatedUser.getPrincipal(), msg.id(), Duration.ZERO);
                                                          Notification.show("Resurrected deadletter : " + msg.id());
                                                          deadLetterSearchField.clear();
                                                          deadLetterMessagesGrid.getDataProvider().refreshAll();
                                                          updateQueuedTotals(msg.queueName());
                                                      })
                                                     ).setHeader("Actions");
            deadLetterMessagesGrid.addComponentColumn(msg -> new Button("Delete", click -> {
                                                          durableQueuesApi.deleteMessage(authenticatedUser.getPrincipal(), msg.id());
                                                          Notification.show("Deleted deadletter : " + msg.id());
                                                          queuedSearchField.clear();
                                                          queuedMessagesGrid.getDataProvider().refreshAll();
                                                          deadLetterMessagesGrid.getDataProvider().refreshAll();
                                                          updateQueuedTotals(msg.queueName());
                                                      })
                                                     ).setHeader("Delete");
        }
        deadLetterMessagesGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
    }

    private String truncatePayload(String payload) {
        return payload != null && payload.length() > TRUNCATE_PAYLOAD_AT_LENGTH
               ? payload.substring(0, TRUNCATE_PAYLOAD_AT_LENGTH) + "..."
               : payload;
    }

    private Span createHeaderWithTooltip(String text) {
        Span header = new Span(text);
        header.getElement().setProperty("title", text);
        return header;
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? dateTimeFormatter.format(dateTime) : "";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!securityUtils.canAccessQueues()) {
            event.forwardTo(AccessDeniedView.class);
        }
    }
}
