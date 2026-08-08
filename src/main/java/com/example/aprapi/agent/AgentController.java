package com.example.aprapi.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "apr.agent", name = "enabled", havingValue = "true")
public class AgentController {

	private final AgentRunnerService agentRunnerService;

	public AgentController(AgentRunnerService agentRunnerService) {
		this.agentRunnerService = agentRunnerService;
	}

	@PostMapping("/api/compliance/review")
	public AgentReviewResponse review() {
		return agentRunnerService.review();
	}
}
