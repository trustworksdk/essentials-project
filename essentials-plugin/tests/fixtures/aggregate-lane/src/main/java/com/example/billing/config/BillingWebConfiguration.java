package com.example.billing.config;

import dk.trustworks.essentials.types.spring.web.EssentialsWebFluxConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(EssentialsWebFluxConfigurer.class)
public class BillingWebConfiguration {
}
