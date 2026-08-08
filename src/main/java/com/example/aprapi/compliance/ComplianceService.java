package com.example.aprapi.compliance;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs the compliance rules and returns their report.
 *
 * <p>The rules themselves deliberately do <strong>not</strong> live here. They live in
 * {@code .claude/skills/rate-compliance/scripts/check_compliance.py}, a standalone script
 * that can be copied to any project and run without this application. This class only
 * invokes it and parses the result, so there is exactly one implementation of the policy
 * rather than one here and another in the skill that would drift apart.
 */
@Service
public class ComplianceService {

	private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The script's contract: 0 compliant, 1 violations found, 2 could not run. */
	private static final int EXIT_COMPLIANT = 0;
	private static final int EXIT_VIOLATIONS = 1;

	private final ComplianceProperties properties;
	private final ExcelProperties excelProperties;
	private final ProcessRunner processRunner;

	public ComplianceService(ComplianceProperties properties,
							 ExcelProperties excelProperties,
							 ProcessRunner processRunner) {
		this.properties = properties;
		this.excelProperties = excelProperties;
		this.processRunner = processRunner;
	}

	public ComplianceReport check() {
		Path projectDir = Paths.get(properties.projectDir()).toAbsolutePath();
		Path script = projectDir.resolve(properties.scriptPath());
		if (!Files.isRegularFile(script)) {
			throw new ComplianceCheckException("Compliance script not found at " + script);
		}

		List<String> command = List.of(
				properties.pythonPath(),
				script.toString(),
				"--file", Paths.get(excelProperties.path()).toAbsolutePath().toString(),
				"--format", "json");

		ProcessRunner.Result result =
				processRunner.run(command, projectDir.toFile(), properties.timeoutSeconds());

		if (!result.started()) {
			throw new ComplianceCheckException(
					"Could not run the compliance script. Is '" + properties.pythonPath()
							+ "' on PATH? " + result.stderr().trim());
		}
		if (result.timedOut()) {
			throw new ComplianceCheckException(
					"Compliance check timed out after " + properties.timeoutSeconds() + "s");
		}
		// Exit 1 means violations were found, which is a normal outcome with a valid report
		// on stdout. Only other non-zero codes indicate the check could not run.
		if (result.exitCode() != EXIT_COMPLIANT && result.exitCode() != EXIT_VIOLATIONS) {
			throw new ComplianceCheckException("Compliance script failed (exit "
					+ result.exitCode() + "): " + result.diagnostics());
		}

		try {
			return MAPPER.readValue(result.stdout(), ComplianceReport.class);
		} catch (RuntimeException e) {
			log.warn("Could not parse compliance script output: {}", result.diagnostics(), e);
			throw new ComplianceCheckException(
					"Compliance script produced output that could not be parsed: "
							+ result.diagnostics());
		}
	}
}
