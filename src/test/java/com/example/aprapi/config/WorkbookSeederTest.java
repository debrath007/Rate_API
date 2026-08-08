package com.example.aprapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbookSeederTest {

	@TempDir
	Path tempDir;

	private void seedTo(Path target) {
		new WorkbookSeeder(new ExcelProperties(target.toString())).seedIfAbsent();
	}

	@Test
	void createsTheWorkbookWhenItIsAbsent() {
		Path target = tempDir.resolve("APR_Report.xlsx");

		seedTo(target);

		assertThat(target).exists();
		assertThat(target).isNotEmptyFile();
	}

	@Test
	void createsMissingParentDirectories() {
		Path target = tempDir.resolve("nested").resolve("deeper").resolve("APR_Report.xlsx");

		seedTo(target);

		assertThat(target).exists();
	}

	@Test
	void neverOverwritesAnExistingWorkbook() throws IOException {
		// The file on disk carries real rate adjustments once the app has run. Re-seeding
		// over it would silently discard them.
		Path target = tempDir.resolve("APR_Report.xlsx");
		Files.writeString(target, "existing data that must survive");

		seedTo(target);

		assertThat(Files.readString(target)).isEqualTo("existing data that must survive");
	}

	@Test
	void seedingIsIdempotent() throws IOException {
		Path target = tempDir.resolve("APR_Report.xlsx");

		seedTo(target);
		long firstSize = Files.size(target);
		byte[] afterFirst = Files.readAllBytes(target);
		seedTo(target);

		assertThat(Files.size(target)).isEqualTo(firstSize);
		assertThat(Files.readAllBytes(target)).isEqualTo(afterFirst);
	}
}
