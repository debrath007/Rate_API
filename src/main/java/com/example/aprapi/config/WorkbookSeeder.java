package com.example.aprapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Puts a writable workbook at the configured path on first run.
 *
 * <p>The pristine workbook ships as a classpath resource so the project is self-contained,
 * but the application writes to it ({@code POST /api/rates/adjust}). A packaged jar's
 * resources are read-only, and in development Gradle's {@code processResources} rewrites
 * {@code build/resources/main} on every build — so writing back to the resource itself
 * would either fail outright or be silently reverted by the next build. The resource is
 * therefore treated as a seed and copied out once.
 *
 * <p>Existing files are never overwritten: after the first run the copy on disk is the
 * source of truth, and re-seeding would throw away real rate adjustments. Deleting the
 * file is the deliberate way to reset it to the shipped data.
 */
@Component
public class WorkbookSeeder {

	private static final Logger log = LoggerFactory.getLogger(WorkbookSeeder.class);
	private static final String SEED_RESOURCE = "APR_Report.xlsx";

	private final ExcelProperties properties;

	public WorkbookSeeder(ExcelProperties properties) {
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seedIfAbsent() {
		Path target = Paths.get(properties.path()).toAbsolutePath();

		if (Files.exists(target)) {
			log.info("Using existing workbook at {}", target);
			return;
		}

		ClassPathResource seed = new ClassPathResource(SEED_RESOURCE);
		if (!seed.exists()) {
			// Not fatal: the path may legitimately point at a workbook supplied elsewhere,
			// and the repository reports a missing file far more clearly than this could.
			log.warn("No workbook at {} and no seed resource '{}' to create one from",
					target, SEED_RESOURCE);
			return;
		}

		try (InputStream in = seed.getInputStream()) {
			Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			log.info("Seeded workbook at {} from classpath resource '{}'", target, SEED_RESOURCE);
		} catch (IOException e) {
			log.error("Could not seed workbook at {}", target, e);
		}
	}
}
