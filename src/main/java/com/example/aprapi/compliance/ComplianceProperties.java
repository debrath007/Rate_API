package com.example.aprapi.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apr.compliance")
public record ComplianceProperties(
		String pythonPath,
		String scriptPath,
		long timeoutSeconds,
		String projectDir) {
}
