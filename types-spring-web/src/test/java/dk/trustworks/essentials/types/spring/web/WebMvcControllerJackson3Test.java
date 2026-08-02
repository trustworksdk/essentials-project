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

package dk.trustworks.essentials.types.spring.web;

import dk.trustworks.essentials.types.spring.web.model.CustomerId;
import dk.trustworks.essentials.types.spring.web.model.DueDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = WebMvcJackson3SpringWebApplication.class)
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "essentials.jackson.flavor", matches = "jackson3")
class WebMvcControllerJackson3Test {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void customerId_response_uses_jackson3_shape() throws Exception {
        var customerId = CustomerId.random();
        mockMvc.perform(get("/order/for-customer/{customerId}", customerId))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("\"customerId\":{\"bytes\":")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("\"value\":\"" + customerId + "\"")));
    }

    @Test
    void dueDate_response_uses_jackson3_shape() throws Exception {
        var dueDate = DueDate.now();
        mockMvc.perform(get("/orders/by-due-date/{dueDate}", dueDate))
               .andExpect(status().isOk())
               .andExpect(content().string(org.hamcrest.Matchers.containsString("\"value\":\"" + dueDate + "\"")))
               .andExpect(content().string(org.hamcrest.Matchers.containsString("\"year\":" + dueDate.value().getYear())));
    }
}
