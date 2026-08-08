package com.example.aprapi.compliance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComplianceController {

	private final ComplianceService complianceService;

	public ComplianceController(ComplianceService complianceService) {
		this.complianceService = complianceService;
	}

	@GetMapping("/api/compliance/check")
	public ComplianceReport check() {
		return complianceService.check();
	}
}
