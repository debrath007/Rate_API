package com.example.aprapi.backup;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.repository.ExcelAprRepository;
import com.example.aprapi.service.AprRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BackupServiceTest {

	@TempDir
	Path tempDir;

	private BackupService backupService;
	private AprRateService rateService;
	private Path workbook;

	@BeforeEach
	void setUp() throws IOException {
		workbook = tempDir.resolve("APR_Report.xlsx");
		try (InputStream fixture = Objects.requireNonNull(
				getClass().getResourceAsStream("/APR_Report_test.xlsx"))) {
			Files.copy(fixture, workbook);
		}
		ExcelProperties properties = new ExcelProperties(workbook.toString());
		backupService = new BackupService(properties);
		rateService = new AprRateService(
				new ExcelAprRepository(properties), backupService, new WorkbookLock());
	}

	@Test
	void listBackups_isEmptyBeforeAnyBackupIsTaken() {
		assertThat(backupService.listBackups()).isEmpty();
	}

	@Test
	void createBackup_copiesTheWorkbookByteForByte() throws IOException {
		byte[] original = Files.readAllBytes(workbook);

		String backupName = backupService.createBackup();

		Path backup = tempDir.resolve("backups").resolve(backupName);
		assertThat(backup).exists();
		assertThat(Files.readAllBytes(backup)).isEqualTo(original);
		assertThat(backupService.listBackups()).containsExactly(backupName);
	}

	@Test
	void adjustAllRates_takesABackupBeforeMutating() {
		String backupName = rateService.adjustAllRates(-1.0).backupFile();

		assertThat(backupName).isNotBlank();
		assertThat(tempDir.resolve("backups").resolve(backupName)).exists();
		// The live workbook moved, so the backup must hold the pre-adjust value.
		assertThat(rateService.getRatesForAccount("ACC00001").rates().get(0).rate())
				.isCloseTo(9.00, within(0.001)); // index 7.25 + margin 1.75
	}

	@Test
	void restoreLatest_returnsTheWorkbookToItsPreAdjustState() {
		double before = rateService.getRatesForAccount("ACC00001").rates().get(0).rate();

		rateService.adjustAllRates(-1.0);
		assertThat(rateService.getRatesForAccount("ACC00001").rates().get(0).rate())
				.isNotCloseTo(before, within(0.001));

		String restoredFrom = backupService.restoreLatest();

		assertThat(restoredFrom).isNotBlank();
		assertThat(rateService.getRatesForAccount("ACC00001").rates().get(0).rate())
				.isCloseTo(before, within(0.001));
	}

	@Test
	void restoreLatest_withNoBackups_returnsNull() {
		assertThat(backupService.restoreLatest()).isNull();
	}

	@Test
	void createBackup_twiceInTheSameSecond_doesNotOverwriteTheFirst() {
		String first = backupService.createBackup();
		String second = backupService.createBackup();

		assertThat(second).isNotEqualTo(first);
		assertThat(backupService.listBackups()).containsExactlyInAnyOrder(first, second);
	}
}
