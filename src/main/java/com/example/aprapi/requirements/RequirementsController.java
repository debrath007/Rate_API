package com.example.aprapi.requirements;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class RequirementsController {

	private final RequirementsService requirementsService;

	public RequirementsController(RequirementsService requirementsService) {
		this.requirementsService = requirementsService;
	}

	@GetMapping("/api/requirements")
	public JsonNode requirements() {
		return requirementsService.catalogue();
	}
}
