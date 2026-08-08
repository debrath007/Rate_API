package com.example.aprapi.testrun;

import com.example.aprapi.testrun.dto.TestRunResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "apr.test-runner", name = "enabled", havingValue = "true")
public class TestRunController {

	private final TestRunnerService testRunnerService;

	public TestRunController(TestRunnerService testRunnerService) {
		this.testRunnerService = testRunnerService;
	}

	@PostMapping("/api/tests/run")
	public TestRunResponse runTests(@RequestParam(defaultValue = "all") String scope) {
		// Spring's default enum binding is case-sensitive; the UI sends lowercase.
		return testRunnerService.runTests(TestScope.valueOf(scope.trim().toUpperCase()));
	}
}
