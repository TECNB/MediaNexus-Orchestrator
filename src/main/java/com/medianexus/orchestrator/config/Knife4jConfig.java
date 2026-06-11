package com.medianexus.orchestrator.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    private static final String BEARER_AUTH_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI mediaNexusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MediaNexus Orchestrator API")
                        .description("Java backend APIs for MediaNexus orchestration.")
                        .version("0.1.0"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("Sa-Token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME));
    }

    @Bean
    public OpenApiCustomizer authorizationHeaderCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                if (!isProtectedApiPath(path)) {
                    return;
                }
                pathItem.readOperations().forEach(this::addAuthorizationHeader);
            });
        };
    }

    private boolean isProtectedApiPath(String path) {
        return path != null
                && path.startsWith("/api/")
                && !"/api/v1/auth/register".equals(path)
                && !"/api/v1/auth/login".equals(path)
                && !"/api/v1/health".equals(path);
    }

    private void addAuthorizationHeader(Operation operation) {
        if (operation == null || hasAuthorizationHeader(operation)) {
            return;
        }
        operation.addParametersItem(new Parameter()
                .in("header")
                .name("Authorization")
                .description("登录后填写：Bearer <token>")
                .required(false)
                .schema(new StringSchema()));
    }

    private boolean hasAuthorizationHeader(Operation operation) {
        return operation.getParameters() != null
                && operation.getParameters().stream()
                .anyMatch(parameter -> "header".equalsIgnoreCase(parameter.getIn())
                        && "Authorization".equalsIgnoreCase(parameter.getName()));
    }
}
