package com.example.aprapi.agent;

import com.example.aprapi.compliance.ComplianceReport;

/**
 * The deterministic report always populates. {@code narrative} and {@code agentError} are
 * mutually exclusive: when the CLI cannot be reached the findings still reach the caller.
 */
public record AgentReviewResponse(
		ComplianceReport report,
		String narrative,
		String agentError,
		double agentSeconds) {
}
