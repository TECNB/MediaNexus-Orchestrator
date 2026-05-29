package com.medianexus.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI mediaNexusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MediaNexus Orchestrator API")
                        .description("Java backend APIs for MediaNexus orchestration.")
                        .version("0.1.0"));
    }
}
