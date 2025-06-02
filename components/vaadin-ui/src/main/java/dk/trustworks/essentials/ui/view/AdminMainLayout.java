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

package dk.trustworks.essentials.ui.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.avatar.*;
import com.vaadin.flow.component.button.*;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.*;
import com.vaadin.flow.component.menubar.*;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.sidenav.*;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.util.SecurityUtils;
import jakarta.annotation.security.PermitAll;

import static com.vaadin.flow.theme.lumo.LumoUtility.*;

/**
 * A layout component for the admin section of the application. Provides a
 * navigation drawer, a header with app branding, and a user menu.
 * <p>
 * The admin layout manages the visual and functional hierarchy of the
 * application's administration user interface. It includes a navigation
 * side drawer, a header bar, and user actions such as logout.
 */
@Layout
@UIScope
@PermitAll
@SpringComponent
@PageTitle("Essentials admin")
public class AdminMainLayout extends AppLayout {

    private final EssentialsAuthenticatedUser authenticatedUser;
    private final SecurityUtils securityUtils;

    public AdminMainLayout(EssentialsAuthenticatedUser authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
        this.securityUtils = new SecurityUtils(authenticatedUser);
        createHeader();
        setPrimarySection(Section.DRAWER);
        createDrawerContent();
    }

    private void createHeader() {
        Button toggleDrawerButton = new Button(VaadinIcon.MENU.create(), event -> {
            setDrawerOpened(!isDrawerOpened());
        });
        toggleDrawerButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Icon appLogo = VaadinIcon.CLUSTER.create();
        appLogo.addClassNames(TextColor.PRIMARY, IconSize.LARGE);

        Span title = new Span("Essentials Admin");
        title.addClassNames(FontWeight.SEMIBOLD, FontSize.LARGE);

        HorizontalLayout headerLayout = new HorizontalLayout(toggleDrawerButton, appLogo, title);
        headerLayout.setAlignItems(FlexComponent.Alignment.CENTER);
        headerLayout.setWidthFull();
        headerLayout.addClassName("app-header");
        addToNavbar(headerLayout);
    }

    /**
     * Creates the drawer content which contains the side navigation and the user menu.
     */
    private void createDrawerContent() {
        SideNav sideNav = createSideNav();
        Component userMenu = createUserMenu();

        VerticalLayout drawerContent = new VerticalLayout();
        drawerContent.setSizeFull();
        drawerContent.setPadding(false);
        drawerContent.setSpacing(false);
        drawerContent.add(sideNav);

        Div spacer = new Div();
        spacer.getStyle().set("flex-grow", "1");
        drawerContent.add(spacer);
        drawerContent.add(userMenu);

        addToDrawer(drawerContent);
    }

    /**
     * Creates the side navigation component.
     */
    private SideNav createSideNav() {
        var nav = new SideNav();
        nav.addClassNames(Margin.Horizontal.MEDIUM);
        if (authenticatedUser.isAuthenticated()) {
            nav.addItem(createSideNavItem("Admin", "", VaadinIcon.HOME.create()));
            if (securityUtils.canAccessLocks()) {
                nav.addItem(createSideNavItem("Locks", "locks", VaadinIcon.LOCK.create()));
            }
            if (securityUtils.canAccessQueues()) {
                nav.addItem(createSideNavItem("Queues", "queues", VaadinIcon.LIST.create()));
            }
            if (securityUtils.canAccessSubscriptions()) {
                nav.addItem(createSideNavItem("Subscriptions", "subscriptions", VaadinIcon.ENVELOPE.create()));
                nav.addItem(createSideNavItem("EventProcessors", "eventprocessors", VaadinIcon.COG.create()));
            }
            if (securityUtils.canAccessPostgresqlStats()) {
                nav.addItem(createSideNavItem("Postgresql stats", "postgresql", VaadinIcon.DATABASE.create()));
            }
            if (securityUtils.canAccessScheduler()) {
                nav.addItem(createSideNavItem("Scheduled jobs", "scheduler", VaadinIcon.CALENDAR_CLOCK.create()));
            }
        }
        return nav;
    }

    private SideNavItem createSideNavItem(String title, String path, Icon icon) {
        return new SideNavItem(title, path, icon);
    }

    private Component createUserMenu() {
        String user = authenticatedUser.getPrincipalName().orElse("");

        Avatar avatar = new Avatar(user);
        avatar.addThemeVariants(AvatarVariant.LUMO_XSMALL);
        avatar.addClassNames(Margin.Right.SMALL);
        avatar.setColorIndex(5);

        MenuBar userMenu = new MenuBar();
        userMenu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE);
        userMenu.addClassNames(Margin.MEDIUM);

        if (authenticatedUser.isAuthenticated()) {
            var userMenuItem = userMenu.addItem(avatar);
            userMenuItem.add(user);
            userMenuItem.getSubMenu().addItem("Logout", e -> {
                authenticatedUser.logout();
            });
        }
        return userMenu;
    }
}

