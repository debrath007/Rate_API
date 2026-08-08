package com.example.aprapi.controller;

import com.example.aprapi.dto.AccountRatesResponse;
import com.example.aprapi.dto.AdjustRateRequest;
import com.example.aprapi.dto.AdjustRateResponse;
import com.example.aprapi.service.AprRateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AprRateController {

	private final AprRateService aprRateService;

	public AprRateController(AprRateService aprRateService) {
		this.aprRateService = aprRateService;
	}

	@GetMapping("/api/accounts/{accountId}/rate")
	public AccountRatesResponse getRate(@PathVariable String accountId) {
		return aprRateService.getRatesForAccount(accountId);
	}

	@PostMapping("/api/rates/adjust")
	public AdjustRateResponse adjustRates(@Valid @RequestBody AdjustRateRequest request) {
		return aprRateService.adjustAllRates(request.percentage());
	}
}
