package io.tokenrail;

import io.tokenrail.config.TokenRailProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(TokenRailProperties.class)
public class TokenRailApplication {
    public static void main(String[] args) {
        SpringApplication.run(TokenRailApplication.class, args);
    }
}
