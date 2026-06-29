package com.skybook.praveen.flightservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI skyBookOpenApi() {

        return new OpenAPI()

                .info(new Info()

                        .title("SkyBook Flight Service API")

                        .description("""
                                Flight Management APIs for SkyBook Airline Reservation System.

                                Features:
                                - Flight CRUD
                                - Flight Search
                                - Flight Operations
                                - Schedule Management
                                - Admin Operations
                                """)

                        .version("v1.0.0")

                        .contact(new Contact()
                                .name("Praveen Somireddy")
                                .email("praveen.somireddy@gmail.com"))

                        .license(new License()
                                .name("Apache 2.0")));
    }

}