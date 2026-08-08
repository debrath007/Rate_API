package com.example.aprapi.controller;

import com.example.aprapi.dto.AccountRatesResponse;
import com.example.aprapi.dto.AdjustRateRequest;
import com.example.aprapi.dto.AdjustRateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AprRateControllerIT {

	@TempDir
	static Path tempDir;

	private static Path workbookCopy;

	@Autowired
	private TestRestTemplate restTemplate;

	@DynamicPropertySource
	static void excelPath(DynamicPropertyRegistry registry) throws IOException {
		workbookCopy = tempDir.resolve("APR_Report_test.xlsx");
		try (InputStream fixture = Objects.requireNonNull(
				AprRateControllerIT.class.getResourceAsStream("/APR_Report_test.xlsx"))) {
			Files.copy(fixture, workbookCopy);
		}
		registry.add("apr.excel.path", () -> workbookCopy.toString());
	}

	@Test
	void getRate_knownAccount_returnsEffectiveRates() {
		ResponseEntity<AccountRatesResponse> response =
				restTemplate.getForEntity("/api/accounts/ACC00001/rate", AccountRatesResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().rates()).hasSize(2);
		assertThat(response.getBody().rates().get(1).rate()).isEqualTo(6.00); // SCRA override wins
	}

	@Test
	void getRate_unknownAccount_returns404() {
		ResponseEntity<String> response =
				restTemplate.getForEntity("/api/accounts/ACC99999/rate", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void adjustRates_appliesPercentageAndReturnsRowsUpdated() {
		AdjustRateRequest request = new AdjustRateRequest(-10.0);

		ResponseEntity<AdjustRateResponse> response =
				restTemplate.postForEntity("/api/rates/adjust", request, AdjustRateResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo("success");
		assertThat(response.getBody().rowsUpdated()).isEqualTo(4);

		ResponseEntity<AccountRatesResponse> after =
				restTemplate.getForEntity("/api/accounts/ACC00001/rate", AccountRatesResponse.class);
		assertThat(after.getBody().rates().get(0).rate()).isEqualTo(9.00); // 10 * 0.9
		assertThat(after.getBody().rates().get(1).rate()).isEqualTo(5.40); // 6 * 0.9
	}
}
