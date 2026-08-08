package com.example.aprapi.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apr.agent")
public record AgentRunnerProperties(
		boolean enabled,
		String cliPath,
		long timeoutSeconds,
		String projectDir) {
}
