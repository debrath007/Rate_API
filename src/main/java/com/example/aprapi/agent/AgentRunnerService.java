package com.example.aprapi.agent;

import com.example.aprapi.compliance.ComplianceReport;
import com.example.aprapi.compliance.ComplianceService;
import com.example.aprapi.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the Claude CLI as the rate-policy SME to interpret a compliance report.
 *
 * <p>The agent is never asked to compute anything: {@link ComplianceService} produces the
 * findings deterministically and they are handed over as fixed input. The agent supplies
 * judgement — impact, root cause, remediation.
 *
 * <p>This shells out to an external binary, so it is gated behind {@code apr.agent.enabled}
 * and every failure path degrades to returning the report without a narrative.
 */
@Service
public class AgentRunnerService {

	private static final Logger log = LoggerFactory.getLogger(AgentRunnerService.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AgentRunnerProperties properties;
	private final ComplianceService complianceService;
	private final ProcessRunner processRunner;
	/** One review at a time; each run is a paid API call, so no accidental fan-out. */
	private final ReentrantLock runLock = new ReentrantLock();

	public AgentRunnerService(AgentRunnerProperties properties,
							  ComplianceService complianceService,
							  ProcessRunner processRunner) {
		this.properties = properties;
		this.complianceService = complianceService;
		this.processRunner = processRunner;
	}

	public AgentReviewResponse review() {
		ComplianceReport report = complianceService.check();

		if (!runLock.tryLock()) {
			return degraded(report, "A policy review is already in progress");
		}
		try {
			return runAgent(report);
		} finally {
			runLock.unlock();
		}
	}

	private AgentReviewResponse runAgent(ComplianceReport report) {
		Path projectDir = Paths.get(properties.projectDir()).toAbsolutePath();
		Path skillFile = projectDir.resolve(".claude/skills/rate-compliance/SKILL.md");
		if (!Files.isRegularFile(skillFile)) {
			return degraded(report, "Skill file not found at " + skillFile);
		}

		// The prompt is multi-line JSON. Passing it as an argv element gets mangled by Windows
		// command-line quoting, which silently truncates the violation list the agent sees, so
		// it goes over stdin instead.
		List<String> command = List.of(
				properties.cliPath(),
				"-p",
				"--allowedTools", "Read",
				"--output-format", "json");

		long start = System.nanoTime();
		ProcessRunner.Result result = processRunner.run(
				command, projectDir.toFile(), properties.timeoutSeconds(), buildPrompt(report));

		if (!result.started()) {
			return degraded(report, "Could not start the Claude CLI ('" + properties.cliPath()
					+ "'): " + result.stderr().trim());
		}
		if (result.timedOut()) {
			return degraded(report,
					"Policy review timed out after " + properties.timeoutSeconds() + "s");
		}

		double seconds = round2((System.nanoTime() - start) / 1_000_000_000.0);
		String raw = result.stdout().trim();

		if (result.exitCode() != 0) {
			log.warn("Claude CLI exited with {}: {}", result.exitCode(), tail(result.diagnostics()));
			return degraded(report, "Claude CLI exited with code " + result.exitCode()
					+ ". " + tail(result.diagnostics()));
		}

		String narrative = extractResult(raw);
		if (narrative == null || narrative.isBlank()) {
			return degraded(report, "Claude CLI returned no usable output. " + tail(raw));
		}
		return new AgentReviewResponse(report, narrative, null, seconds);
	}

	private String buildPrompt(ComplianceReport report) {
		String reportJson;
		try {
			reportJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
		} catch (RuntimeException e) {
			reportJson = "{\"status\":\"" + report.status() + "\"}";
		}

		return """
				You are the rate-policy-sme defined in .claude/agents/rate-policy-sme.md.
				Read .claude/skills/rate-compliance/SKILL.md and apply it.

				Below is a compliance report produced by the application. Its numbers are computed \
				row by row from the spreadsheet and are authoritative - do not recount rows or \
				recompute rates. Interpret the findings: give a one-line verdict, group violations \
				by root cause rather than listing each account separately, state the customer and \
				regulatory impact, and recommend ordered remediation.

				Respond in plain prose with short markdown headings. Do not use code blocks.

				COMPLIANCE REPORT:
				%s
				""".formatted(reportJson);
	}

	/**
	 * The CLI's json output format wraps the reply in an envelope with a "result" field.
	 * Returns null rather than the raw payload when that field cannot be found: handing the
	 * caller a whole JSON envelope to render as prose is worse than admitting failure.
	 */
	private String extractResult(String raw) {
		try {
			JsonNode root = MAPPER.readTree(raw);
			JsonNode result = root.get("result");
			if (result != null && !result.isNull()) {
				return result.asString();
			}
			log.warn("Claude CLI JSON had no 'result' field");
			return null;
		} catch (RuntimeException e) {
			log.warn("Claude CLI output was not valid JSON", e);
			return null;
		}
	}

	private AgentReviewResponse degraded(ComplianceReport report, String error) {
		return new AgentReviewResponse(report, null, error, 0.0);
	}

	private String tail(String text) {
		int maxChars = 500;
		return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
	}

	private double round2(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
