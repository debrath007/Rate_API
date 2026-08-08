package com.example.aprapi.testrun.dto;

import java.util.List;

public record TestRunResponse(
		String status,
		String scope,
		int total,
		int passed,
		int failed,
		int skipped,
		double durationSeconds,
		int exitCode,
		boolean timedOut,
		String message,
		List<TestSuiteResult> suites) {
}
