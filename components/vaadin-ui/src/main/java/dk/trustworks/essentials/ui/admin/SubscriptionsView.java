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

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.*;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * SubscriptionsView is a Vaadin view class responsible for managing
 * the user interface for administering EventStore subscriptions.
 * <p>
 * This view provides functionalities such as displaying a grid of all
 * subscriptions, refreshing subscription data, and querying the highest
 * global event order for specific subscriptions. It is designed to provide
 * a convenient way for system administrators to view and manage
 * EventStore-related operations.
 */
@UIScope
@PermitAll
@SpringComponent
@Route(value = "subscriptions", layout = AdminMainLayout.class)
public class SubscriptionsView extends VerticalLayout implements BeforeEnterObserver {

    private final EventStoreApi               eventStoreApi;
    private final EssentialsAuthenticatedUser authenticatedUser;

    private final Grid<ApiSubscription>       grid = new Grid<>(ApiSubscription.class);

    public SubscriptionsView(EventStoreApi eventStoreApi, EssentialsAuthenticatedUser authenticatedUser) {
        this.eventStoreApi = eventStoreApi;
        this.authenticatedUser = authenticatedUser;
        setSizeFull();
        configureGrid();
        add(new H3("EventStore subscriptions administration"),
                createRefreshButton(),
                new Span("Be advised that calling Load highest global order frequently can effect EventStore performance."),
                grid
        );
        updateGrid();
    }

    private Button createRefreshButton() {
        return new Button("Refresh", click -> updateGrid());
    }

    private void configureGrid() {
        grid.removeAllColumns();

        grid.addColumn(ApiSubscription::subscriberId)
                .setHeader("Subscriber Id").setAutoWidth(true);

        grid.addColumn(ApiSubscription::aggregateType)
                .setHeader("Aggregate Type");

        grid.addColumn(ApiSubscription::currentGlobalOrder)
                .setHeader("Resume From Global Order");

        grid.addColumn(ApiSubscription::lastUpdated)
                .setHeader("Last Updated");

        grid.addComponentColumn(subscription -> {
            Div container = new Div();
            Button loadButton = new Button("Load Highest");
            loadButton.addClickListener(click -> {
                var highest = eventStoreApi.findHighestGlobalEventOrderPersisted(authenticatedUser.getPrincipal(), subscription.aggregateType());
                container.removeAll();
                if (highest.isPresent()) {
                    container.add(new Span(String.valueOf(highest.get().getValue())));
                } else {
                    container.add(new Span("No highest event order found"));
                }
            });
            container.add(loadButton);
            return container;
        }).setHeader("Highest Global Order");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
    }

    private void updateGrid() {
        List<ApiSubscription> subscriptions = eventStoreApi.findAllSubscriptions(authenticatedUser.getPrincipal());
        grid.setItems(subscriptions);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!authenticatedUser.hasSubscriptionReaderRole() && !authenticatedUser.hasAdminRole()) {
            event.forwardTo(AccessDeniedView.class);
        }
    }
}
