package com.example.aprapi.service;

import com.example.aprapi.backup.BackupService;
import com.example.aprapi.backup.WorkbookLock;
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
		service = new AprRateService(
				new ExcelAprRepository(properties), new BackupService(properties), new WorkbookLock());
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
	void adjustAllRates_repricesVariableRowsFromIndexAndMargin() {
		// Four of the five fixture rows are variable; the fixed one is not repriced.
		assertThat(service.adjustAllRates(1.0).rowsUpdated()).isEqualTo(4);

		AccountRatesResponse acc1 = service.getRatesForAccount("ACC00001");
		assertThat(acc1.rates().get(0).rate()).isCloseTo(11.00, within(0.001)); // index 9.25 + margin 1.75

		AccountRatesResponse acc2 = service.getRatesForAccount("ACC00002");
		assertThat(acc2.rates().get(0).rate()).isCloseTo(9.00, within(0.001)); // index 9.00 + margin 0.00, override higher
		assertThat(acc2.rates().get(1).rate()).isCloseTo(11.00, within(0.001)); // CMA override 10.00 + 1.00
	}

	@Test
	void adjustAllRates_leavesFixedRateRowsAlone() {
		// APR-033: a fixed-rate account does not track the index, so an index movement
		// must not touch it. This is the control case -- it proves a repricing run
		// distinguishes rows that should move from rows that should not.
		service.adjustAllRates(1.0);

		assertThat(service.getRatesForAccount("ACC00003").rates().get(0).rate())
				.isCloseTo(20.00, within(0.001));
	}

	@Test
	void adjustAllRates_movesProtectedOverrideRates_knownDefect() {
		// KNOWN DEFECT, asserted so it is visible rather than silent. APR-098 requires a
		// SCRA-capped account to stay at or below 6.00% "regardless of the prime rate
		// movement". This implementation adds the index change to every override rate,
		// including protected ones, so the account is dragged above the statutory cap.
		//
		// When adjustAllRates is fixed to leave protected overrides alone, this test
		// should fail -- invert it to expect 6.00 rather than deleting it.
		service.adjustAllRates(1.0);

		assertThat(service.getRatesForAccount("ACC00001").rates().get(1).rate())
				.as("SCRA override should have held at 6.00 but tracked the index")
				.isCloseTo(7.00, within(0.001));
	}
}
