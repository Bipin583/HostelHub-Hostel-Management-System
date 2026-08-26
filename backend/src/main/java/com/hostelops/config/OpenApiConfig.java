package com.hostelops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document is the contract the frontend's typed client is generated
 * from, so it is treated as a deliverable rather than a debugging aid: if a
 * response shape is not described here, the client cannot see it.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI hostelOpsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Hostel Operations Platform API")
                        .version("v1")
                        .description("""
                                Room allocation, attendance, complaints, fees and notices.

                                Every error response uses one envelope:
                                `{ "error": { "code", "message", "details" } }`.
                                Codes are stable; messages are for humans and may change.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login")))
                // Applied globally: the endpoints that do not need it are the three
                // auth ones, which is the smaller list to annotate as exceptions.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
