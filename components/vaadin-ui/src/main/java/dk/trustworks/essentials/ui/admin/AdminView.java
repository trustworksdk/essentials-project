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

import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.ui.view.AdminMainLayout;
import jakarta.annotation.security.PermitAll;

/**
 * The AdminView class represents the main view for the administration section of the application.
 * It is a part of the Essentials Admin application and serves as the entry point to the admin UI.
 */
@UIScope
@PermitAll
@SpringComponent
@PageTitle("Essentials admin")
@Route(value = "", layout = AdminMainLayout.class)
public class AdminView extends VerticalLayout {

    public AdminView() {

        add(new H3("Welcome to the Essentials Admin App"));
    }
}
