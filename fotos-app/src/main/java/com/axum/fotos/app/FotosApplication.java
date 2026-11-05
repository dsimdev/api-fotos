package com.axum.fotos.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FotosApplication {

    public static void main(String[] args) {
        SpringApplication.run(FotosApplication.class, args);
    }
}
