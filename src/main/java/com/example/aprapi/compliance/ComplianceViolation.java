package com.example.aprapi.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceViolation(
		String rule,
		String severity,
		String accountId,
		String rateType,
		/** The case the offending row exists to exercise, e.g. SCRA_AT_CAP. */
		String scenario,
		Double expected,
		Double actual,
		String message) {
}
