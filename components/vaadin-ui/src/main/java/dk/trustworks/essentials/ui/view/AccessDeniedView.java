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

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.server.*;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Represents the "Access Denied" view of the application.
 * This view is displayed to users who attempt to access resources
 * they are not authorized to view. The visual layout is centered,
 * user-friendly, and provides navigational support back to the home page.
 */
@AnonymousAllowed
@PageTitle("Access Denied")
@Route(value = "access-denied")
public class AccessDeniedView extends VerticalLayout implements BeforeEnterObserver {

    public AccessDeniedView() {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);
        getStyle().set("text-align", "center");
        getStyle().set("background-color", "var(--lumo-contrast-10pct)");
        getStyle().set("padding", "var(--lumo-space-xl)");

        H1 title = new H1("🚫 Access Denied");
        title.getStyle().set("margin-bottom", "var(--lumo-space-l)");
        title.getStyle().set("font-size", "2.5rem");
        title.getStyle().set("color", "var(--lumo-error-color)");

        Div messageWrapper = new Div();
        messageWrapper.getStyle().set("max-width", "400px");
        messageWrapper.getStyle().set("margin-bottom", "var(--lumo-space-l)");
        messageWrapper.getStyle().set("font-size", "1rem");
        messageWrapper.getStyle().set("color", "var(--lumo-body-text-color)");
        messageWrapper.add(new Text("Sorry, you don’t have permission to view this page."));

        Button homeButton = new Button("Go to Home");
        homeButton.addClickListener(click -> {
            homeButton.getUI().ifPresent(ui -> ui.navigate(""));
        });
        homeButton.getElement().setAttribute("theme", "primary");
        homeButton.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m)");
        homeButton.getStyle().set("font-size", "1rem");

        add(title, messageWrapper, new Div(homeButton));
    }

    /**
     * When Vaadin forwards here, it will trigger this BeforeEnterObserver. We use it to
     * set the HTTP status code to 403 (Forbidden), so that crawlers and security audits
     * see the correct status.
     */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        VaadinResponse response = VaadinService.getCurrentResponse();
        if (response != null) {
            response.setStatus(403);
        }
    }
}
