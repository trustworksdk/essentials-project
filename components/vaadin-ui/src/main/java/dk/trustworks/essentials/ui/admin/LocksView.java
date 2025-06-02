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
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.util.SecurityUtils;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The LocksView class represents a user interface view for managing fenced locks.
 * This view provides an administrative interface to display and interact with locks,
 * allowing operations like refreshing lock details and releasing locks.
 * <p>
 * The view is restricted to authenticated users with appropriate roles, ensuring
 * secure access to lock administration functionality.
 * <p>
 * An authenticated user can perform the following tasks:
 * - View the current state of fenced locks.
 * - Release selected locks, if the user has sufficient privileges.
 * - Refresh the lock grid to synchronize with the latest data.
 */
@UIScope
@PermitAll
@SpringComponent
@Route(value = "locks", layout = AdminMainLayout.class)
public class LocksView extends VerticalLayout implements BeforeEnterObserver {

    private final EssentialsAuthenticatedUser authenticatedUser;
    private final DBFencedLockApi            dbfencedLockApi;
    private final SecurityUtils              securityUtils;

    private final Grid<ApiDBFencedLock>      grid              = new Grid<>(ApiDBFencedLock.class);

    private final DateTimeFormatter          dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public LocksView(EssentialsAuthenticatedUser authenticatedUser, DBFencedLockApi dbfencedLockApi) {
        this.authenticatedUser = authenticatedUser;
        this.securityUtils = new SecurityUtils(authenticatedUser);
        this.dbfencedLockApi = dbfencedLockApi;
        configureGrid();
        add(new H3("Locks Administration"), createRefreshButton(), grid);
        updateGrid();
    }

    private void configureGrid() {
        grid.getElement().getStyle().set("background-color", "#f5f5f5");
        grid.removeAllColumns();

        grid.addColumn(ApiDBFencedLock::lockName)
                .setHeader("Lock Name").setAutoWidth(true);
        grid.addColumn(ApiDBFencedLock::currentToken)
                .setHeader("Current Token");
        grid.addColumn(ApiDBFencedLock::lockedByLockManagerInstanceId)
                .setHeader("Locked By").setAutoWidth(true);
        grid.addColumn(details -> formatDateTime(details.lockAcquiredTimestamp()))
                .setHeader("Lock Acquired").setAutoWidth(true);
        grid.addColumn(details -> formatDateTime(details.lockLastConfirmedTimestamp()))
                .setHeader("Last Confirmed").setAutoWidth(true);

        if (securityUtils.canWriteLocks()) {
            grid.addComponentColumn(details -> new Button("Release", click -> {
                if( dbfencedLockApi.releaseLock(authenticatedUser.getPrincipal(), details.lockName())) {
                    Notification.show("Released lock: " + details.lockName());
                } else {
                    Notification.show("Could not release lock: '" + details.lockName() + "'");
                }
                updateGrid();
            })).setHeader("Actions");
        }

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
    }

    private Button createRefreshButton() {
        return new Button("Refresh", click -> updateGrid());
    }

    private void updateGrid() {
        List<ApiDBFencedLock> locksDetails = dbfencedLockApi.getAllLocks(authenticatedUser.getPrincipal());
        grid.setItems(locksDetails);
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        return dateTime != null ? dateTimeFormatter.format(dateTime): "";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!authenticatedUser.hasLockReaderRole() && !authenticatedUser.hasAdminRole()) {
            event.forwardTo(AccessDeniedView.class);
        }
    }
}
