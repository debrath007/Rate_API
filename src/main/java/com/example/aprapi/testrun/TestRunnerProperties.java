package com.example.aprapi.testrun;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apr.test-runner")
public record TestRunnerProperties(String projectDir, long timeoutSeconds, boolean enabled) {
}
