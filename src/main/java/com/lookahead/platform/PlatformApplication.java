package com.lookahead.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.lookahead.platform", "com.lookahead.learning.content"},
        exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
public class PlatformApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(PlatformApplication.class);
        application.setAdditionalProfiles("accounts", "resource");
        application.run(args);
    }
}
