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

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Represents the login view for administrators of the Essentials Admin system.
 * This view is publicly accessible and is used to provide a user-friendly
 * interface for administrators to log in to the application.
 * <p>
 * Annotations provide the following functionality:
 * - @Route("login"): Defines the routing path for this view.
 * - @AnonymousAllowed: Specifies that this page is accessible without authentication.
 */
@Route("login")
@AnonymousAllowed
public class AdminLoginView extends VerticalLayout {

    public AdminLoginView() {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        var login = new LoginForm();
        login.setAction("login");

        add(new H1("Essentials Admin"),
            login
        );
    }
}
