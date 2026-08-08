package com.example.aprapi.testrun;

import com.example.aprapi.testrun.dto.TestRunResponse;
import com.example.aprapi.testrun.dto.TestSuiteResult;
import com.example.aprapi.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the project's own Gradle test task in a subprocess and reports what it did.
 *
 * <p>This executes a build, so it is a local development aid and not something to expose. The
 * command is assembled from a fixed argument list; the caller's scope selects between hardcoded
 * options and is never interpolated into the command.
 */
@Service
public class TestRunnerService {

	private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);

	/** Matches only the plain unit test class, leaving the Spring integration tests out. */
	private static final String UNIT_TEST_FILTER = "*AprRateServiceTest";

	private final TestRunnerProperties properties;
	private final JUnitXmlParser parser;
	private final ProcessRunner processRunner;
	/** One run at a time: two concurrent Gradle builds would fight over the same output dir. */
	private final ReentrantLock runLock = new ReentrantLock();

	public TestRunnerService(TestRunnerProperties properties, JUnitXmlParser parser,
							 ProcessRunner processRunner) {
		this.properties = properties;
		this.parser = parser;
		this.processRunner = processRunner;
	}

	public TestRunResponse runTests(TestScope scope) {
		if (!runLock.tryLock()) {
			return failure(scope, "A test run is already in progress");
		}
		try {
			return execute(scope);
		} finally {
			runLock.unlock();
		}
	}

	private TestRunResponse execute(TestScope scope) {
		Path projectDir = Paths.get(properties.projectDir()).toAbsolutePath();
		File wrapper = projectDir.resolve(isWindows() ? "gradlew.bat" : "gradlew").toFile();
		if (!wrapper.isFile()) {
			return failure(scope, "Gradle wrapper not found at " + wrapper);
		}

		List<String> command = buildCommand(wrapper, scope);
		log.info("Running tests: {}", String.join(" ", command));

		// Sampled before launch so the parser can ignore XML left behind by earlier runs.
		long startedAt = System.currentTimeMillis();
		long start = System.nanoTime();

		ProcessRunner.Result result =
				processRunner.run(command, projectDir.toFile(), properties.timeoutSeconds());

		if (!result.started()) {
			log.error("Failed to start Gradle: {}", result.stderr());
			return failure(scope, "Failed to start Gradle: " + result.stderr().trim());
		}
		if (result.timedOut()) {
			return failure(scope, "Test run timed out after " + properties.timeoutSeconds() + "s", true);
		}

		int exitCode = result.exitCode();
		double duration = (System.nanoTime() - start) / 1_000_000_000.0;

		Path resultsDir = projectDir.resolve("build").resolve("test-results").resolve("test");
		List<TestSuiteResult> suites = parser.parseResults(resultsDir, startedAt);

		if (suites.isEmpty()) {
			// Gradle can fail before any test runs — a compile error, for instance — in which
			// case its console output is the only useful diagnostic.
			return failure(scope, "No tests ran (Gradle exit code " + exitCode + "). Output:\n"
					+ tail(result.diagnostics()));
		}
		return summarize(scope, suites, duration, exitCode);
	}

	private List<String> buildCommand(File wrapper, TestScope scope) {
		List<String> command = new ArrayList<>();
		command.add(wrapper.getAbsolutePath());
		command.add("test");
		// Without --rerun Gradle reports the task UP-TO-DATE and skips execution entirely,
		// which would leave the previous run's XML to be reported as a fresh result.
		command.add("--rerun");
		// The app is usually launched by `gradlew bootRun`, whose build holds the project cache
		// lock for as long as it runs. A separate cache dir keeps this build from blocking on it.
		command.add("--project-cache-dir");
		command.add("build/test-runner-cache");
		command.add("--console=plain");
		if (scope == TestScope.UNIT) {
			command.add("--tests");
			command.add(UNIT_TEST_FILTER);
		}
		return command;
	}

	private TestRunResponse summarize(TestScope scope, List<TestSuiteResult> suites, double duration, int exitCode) {
		int total = suites.stream().mapToInt(TestSuiteResult::tests).sum();
		int failed = suites.stream().mapToInt(TestSuiteResult::failures).sum();
		int skipped = suites.stream().mapToInt(TestSuiteResult::skipped).sum();
		int passed = total - failed - skipped;

		String status = failed == 0 ? "success" : "failed";
		String message = failed == 0
				? passed + " of " + total + " tests passed"
				: failed + " of " + total + " tests failed";

		return new TestRunResponse(
				status, scope.name().toLowerCase(), total, passed, failed, skipped,
				round2(duration), exitCode, false, message, suites);
	}

	private TestRunResponse failure(TestScope scope, String message) {
		return failure(scope, message, false);
	}

	private TestRunResponse failure(TestScope scope, String message, boolean timedOut) {
		return new TestRunResponse(
				"error", scope.name().toLowerCase(), 0, 0, 0, 0, 0.0, -1, timedOut, message, List.of());
	}

	private String tail(String text) {
		int maxChars = 4000;
		return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
	}

	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private double round2(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
