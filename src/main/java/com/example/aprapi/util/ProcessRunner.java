package com.example.aprapi.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command and collects its result.
 *
 * <p>Centralised because getting this right is fiddly and getting it wrong is subtle:
 * stdout and stderr must be drained concurrently or a full pipe buffer wedges the child
 * before it can exit, and merging the two streams corrupts any structured output the
 * caller needs to parse.
 */
@Component
public class ProcessRunner {

	private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

	/** Result of a completed (or abandoned) run. */
	public record Result(int exitCode, String stdout, String stderr, boolean timedOut, boolean started) {

		public boolean succeeded() {
			return started && !timedOut && exitCode == 0;
		}

		/** stderr when the process wrote any, otherwise stdout — whichever explains a failure. */
		public String diagnostics() {
			return !stderr.isBlank() ? stderr.trim() : stdout.trim();
		}
	}

	public Result run(List<String> command, File workingDir, long timeoutSeconds) {
		return run(command, workingDir, timeoutSeconds, null);
	}

	/**
	 * @param stdin written to the process and then closed; null closes stdin immediately.
	 *              Prefer this over passing large or multi-line values as arguments, which
	 *              Windows command-line quoting will mangle.
	 */
	public Result run(List<String> command, File workingDir, long timeoutSeconds, String stdin) {
		Process process;
		try {
			process = new ProcessBuilder(command).directory(workingDir).start();
		} catch (IOException e) {
			log.warn("Could not start process {}", command.get(0), e);
			return new Result(-1, "", "Could not start '" + command.get(0) + "': " + e.getMessage(),
					false, false);
		}

		try (var out = process.getOutputStream()) {
			if (stdin != null) {
				out.write(stdin.getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException e) {
			process.destroyForcibly();
			return new Result(-1, "", "Failed writing to stdin: " + e.getMessage(), false, true);
		}

		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread outDrain = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
		Thread errDrain = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));

		boolean exited;
		try {
			exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return new Result(-1, stdout.toString(), "Interrupted", false, true);
		}

		if (!exited) {
			process.destroyForcibly();
			join(outDrain);
			join(errDrain);
			return new Result(-1, stdout.toString(), stderr.toString(), true, true);
		}
		join(outDrain);
		join(errDrain);

		return new Result(process.exitValue(), stdout.toString(), stderr.toString(), false, true);
	}

	private void drain(InputStream stream, StringBuilder sink) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (sink) {
					sink.append(line).append(System.lineSeparator());
				}
			}
		} catch (IOException e) {
			log.debug("Stopped reading process output", e);
		}
	}

	private void join(Thread thread) {
		try {
			thread.join(TimeUnit.SECONDS.toMillis(5));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
