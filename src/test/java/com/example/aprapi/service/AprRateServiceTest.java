package com.example.aprapi.service;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.dto.AccountRatesResponse;
import com.example.aprapi.exception.AccountNotFoundException;
import com.example.aprapi.repository.ExcelAprRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AprRateServiceTest {

	@TempDir
	Path tempDir;

	private AprRateService service;
	private Path workbookCopy;

	@BeforeEach
	void setUp() throws IOException {
		workbookCopy = tempDir.resolve("APR_Report_test.xlsx");
		try (InputStream fixture = Objects.requireNonNull(
				getClass().getResourceAsStream("/APR_Report_test.xlsx"))) {
			Files.copy(fixture, workbookCopy);
		}
		ExcelProperties properties = new ExcelProperties(workbookCopy.toString());
		service = new AprRateService(new ExcelAprRepository(properties));
	}

	@Test
	void getRatesForAccount_returnsEffectiveRates() {
		AccountRatesResponse acc1 = service.getRatesForAccount("ACC00001");
		assertThat(acc1.rates()).hasSize(2);
		assertThat(acc1.rates().get(0).rateType()).isEqualTo("01");
		assertThat(acc1.rates().get(0).rate()).isCloseTo(10.00, within(0.001)); // no override
		assertThat(acc1.rates().get(1).rateType()).isEqualTo("02");
		assertThat(acc1.rates().get(1).rate()).isCloseTo(6.00, within(0.001)); // SCRA override wins

		AccountRatesResponse acc2 = service.getRatesForAccount("ACC00002");
		assertThat(acc2.rates().get(0).rate()).isCloseTo(8.00, within(0.001)); // rate wins (override higher)
		assertThat(acc2.rates().get(1).rate()).isCloseTo(10.00, within(0.001)); // CMA override wins
	}

	@Test
	void getRatesForAccount_unknownAccount_throwsNotFound() {
		assertThatThrownBy(() -> service.getRatesForAccount("ACC99999"))
				.isInstanceOf(AccountNotFoundException.class);
	}

	@Test
	void adjustAllRates_updatesOnlyTheEffectiveColumnAndLeavesTheOtherUntouched() {
		assertThat(service.adjustAllRates(-10.0).rowsUpdated()).isEqualTo(4);

		AccountRatesResponse acc1 = service.getRatesForAccount("ACC00001");
		assertThat(acc1.rates().get(0).rate()).isCloseTo(9.00, within(0.001)); // 10 * 0.9, Rate column updated
		assertThat(acc1.rates().get(1).rate()).isCloseTo(5.40, within(0.001)); // 6 * 0.9, OverrideRate column updated

		AccountRatesResponse acc2 = service.getRatesForAccount("ACC00002");
		assertThat(acc2.rates().get(0).rate()).isCloseTo(7.20, within(0.001)); // 8 * 0.9, Rate column updated (override was higher, untouched)
		assertThat(acc2.rates().get(1).rate()).isCloseTo(9.00, within(0.001)); // 10 * 0.9, OverrideRate column updated
	}
}
