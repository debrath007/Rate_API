package com.example.aprapi.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceViolation(
		String rule,
		String severity,
		String accountId,
		String rateType,
		Double expected,
		Double actual,
		String message) {
}
