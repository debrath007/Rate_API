package com.example.aprapi.compliance;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.util.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy rules are tested in
 * {@code .claude/skills/rate-compliance/scripts/test_check_compliance.py}, where they now
 * live. These tests cover only this application's side of the boundary: that it finds the
 * script, runs it, parses what comes back, and fails clearly when it cannot.
 */
class ComplianceServiceTest {

	private static final String PROJECT_DIR = Paths.get("").toAbsolutePath().toString();
	private static final String SCRIPT =
			".claude/skills/rate-compliance/scripts/check_compliance.py";

	@TempDir
	Path tempDir;

	private Path workbook;

	@BeforeEach
	void setUp() throws IOException {
		workbook = tempDir.resolve("APR_Report.xlsx");
		try (InputStream fixture = Objects.requireNonNull(
				getClass().getResourceAsStream("/APR_Report_test.xlsx"))) {
			Files.copy(fixture, workbook);
		}
	}

	private ComplianceService serviceWith(String pythonPath, String scriptPath) {
		return new ComplianceService(
				new ComplianceProperties(pythonPath, scriptPath, 60, PROJECT_DIR),
				new ExcelProperties(workbook.toString()),
				new ProcessRunner());
	}

	private ComplianceService service() {
		return serviceWith("py", SCRIPT);
	}

	@Test
	void runsTheScriptAndParsesACleanReport() {
		ComplianceReport report = service().check();

		assertThat(report.status()).isEqualTo("compliant");
		assertThat(report.violations()).isEmpty();
		assertThat(report.isCompliant()).isTrue();
		assertThat(report.rowsChecked()).isEqualTo(4);
		assertThat(report.accountsChecked()).isEqualTo(2);
		assertThat(report.checkedAt()).isNotBlank();
	}

	@Test
	void exitCodeOneIsAValidReportNotAFailure() throws IOException {
		// Violations make the script exit 1; that must be parsed, not treated as an error.
		writeDriftedScraWorkbook();

		ComplianceReport report = service().check();

		assertThat(report.status()).isEqualTo("violations_found");
		assertThat(report.violations()).isNotEmpty();
		assertThat(report.highCount()).isPositive();

		ComplianceViolation violation = report.violations().get(0);
		assertThat(violation.rule()).isEqualTo("SCRA_FIXED_RATE");
		assertThat(violation.severity()).isEqualTo("HIGH");
		assertThat(violation.expected()).isEqualTo(6.00);
		assertThat(violation.actual()).isEqualTo(5.40);
		assertThat(violation.message()).isNotBlank();
	}

	@Test
	void missingInterpreterFailsWithAnActionableMessage() {
		assertThatThrownBy(() -> serviceWith("python-does-not-exist", SCRIPT).check())
				.isInstanceOf(ComplianceCheckException.class)
				.hasMessageContaining("Could not run the compliance script")
				.hasMessageContaining("python-does-not-exist");
	}

	@Test
	void missingScriptFailsBeforeLaunchingAnything() {
		assertThatThrownBy(() -> serviceWith("py", "does/not/exist.py").check())
				.isInstanceOf(ComplianceCheckException.class)
				.hasMessageContaining("Compliance script not found");
	}

	/** Drops the SCRA override from 6.00% to 5.40%, as a -10% adjustment would. */
	private void writeDriftedScraWorkbook() throws IOException {
		org.apache.poi.ss.usermodel.Workbook wb;
		try (InputStream is = Files.newInputStream(workbook)) {
			wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(is);
		}
		try (wb) {
			wb.getSheetAt(0).getRow(2).getCell(5).setCellValue(0.054);
			try (var out = Files.newOutputStream(workbook)) {
				wb.write(out);
			}
		}
	}
}
