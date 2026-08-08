package com.example.aprapi.testrun.dto;

import java.util.List;

public record TestSuiteResult(
		String className,
		int tests,
		int failures,
		int skipped,
		double timeSeconds,
		String systemOut,
		List<TestCaseResult> cases) {
}
