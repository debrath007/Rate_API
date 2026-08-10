package com.example.aprapi.requirements;

import com.example.aprapi.compliance.ComplianceProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves the product-requirement catalogue that ships with the compliance skill.
 *
 * <p>The catalogue is a build artefact of the requirements document, not something
 * this application computes: it is read and handed over unchanged, so the coverage
 * the console reports is the same coverage {@code test_requirements.py} asserts
 * against. Deriving it a second time here would let the two drift.
 */
@Service
public class RequirementsService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CATALOGUE =
			".claude/skills/rate-compliance/requirements.json";

	private final ComplianceProperties properties;

	public RequirementsService(ComplianceProperties properties) {
		this.properties = properties;
	}

	public JsonNode catalogue() {
		Path path = Paths.get(properties.projectDir()).toAbsolutePath().resolve(CATALOGUE);
		if (!Files.isRegularFile(path)) {
			throw new RequirementsUnavailableException("Requirement catalogue not found at " + path);
		}
		try {
			return MAPPER.readTree(Files.readString(path));
		} catch (IOException | RuntimeException e) {
			throw new RequirementsUnavailableException(
					"Could not read the requirement catalogue at " + path + ": " + e.getMessage());
		}
	}
}
