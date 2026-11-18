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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The operator page {@code /ops} (phase 8 §2): a Thymeleaf shell its script fills from {@link DemoControlAPI}. Operator
 * only (security configuration: everything not customer-facing is). Without the simulators the page says the exchange
 * is not running, and its API does not exist.
 */
@Controller
public class OpsPage {
    @GetMapping("/ops")
    public String ops(@AuthenticationPrincipal UserDetails caller, Model model) {
        model.addAttribute("username", caller != null ? caller.getUsername() : "");
        return "ops/index";
    }
}
