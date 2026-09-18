package com.lookahead.domain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.lookahead.domain", "com.lookahead.learning.content"},
        exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
public class DomainApiApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(DomainApiApplication.class);
        application.setAdditionalProfiles("accounts", "resource");
        application.run(args);
    }
}
