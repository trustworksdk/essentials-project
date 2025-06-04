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

import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;
import com.vaadin.flow.spring.annotation.*;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.ui.util.SecurityUtils;
import dk.trustworks.essentials.ui.view.*;
import jakarta.annotation.security.PermitAll;

@UIScope
@PermitAll
@SpringComponent
@Route(value = "eventprocessors", layout = AdminMainLayout.class)
public class EventProcessorsView extends VerticalLayout implements BeforeEnterObserver {

    private final EssentialsAuthenticatedUser authenticatedUser;
    private final SecurityUtils               securityUtils;

    public EventProcessorsView(EssentialsAuthenticatedUser authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
        this.securityUtils = new SecurityUtils(authenticatedUser);
        setSizeFull();

        add(new H3("EventProcessors"),
            new Paragraph("Coming soon ...")
        );
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (!securityUtils.canAccessSubscriptions()) {
            event.forwardTo(AccessDeniedView.class);
        }
    }
}
