package com.example.aprapi.testrun.dto;

public record TestCaseResult(
		String className,
		String name,
		String status,
		double timeSeconds,
		String failureMessage,
		String failureTrace) {
}
