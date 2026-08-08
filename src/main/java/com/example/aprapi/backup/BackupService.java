package com.example.aprapi.backup;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.exception.ExcelAccessException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Point-in-time copies of the APR workbook, taken before any destructive update so a
 * mistaken adjustment can be rolled back.
 */
@Service
public class BackupService {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final String PREFIX = "APR_Report.backup-";
	private static final String SUFFIX = ".xlsx";

	private final ExcelProperties excelProperties;

	public BackupService(ExcelProperties excelProperties) {
		this.excelProperties = excelProperties;
	}

	/** Copies the live workbook into the backups directory and returns the new file's name. */
	public String createBackup() {
		Path source = excelPath();
		Path backupDir = backupDir();
		try {
			Files.createDirectories(backupDir);
			Path target = uniqueTarget(backupDir, LocalDateTime.now());
			Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
			return target.getFileName().toString();
		} catch (IOException e) {
			throw new ExcelAccessException("Failed to back up APR excel file: " + source, e);
		}
	}

	/** Backup file names, newest first. */
	public List<String> listBackups() {
		Path backupDir = backupDir();
		if (!Files.isDirectory(backupDir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(backupDir)) {
			return files
					.filter(Files::isRegularFile)
					.map(path -> path.getFileName().toString())
					.filter(name -> name.startsWith(PREFIX) && name.endsWith(SUFFIX))
					// Names are timestamped to the second, so lexical order is chronological order.
					.sorted(Comparator.reverseOrder())
					.toList();
		} catch (IOException e) {
			throw new ExcelAccessException("Failed to list backups in: " + backupDir, e);
		}
	}

	/**
	 * Restores the most recent backup over the live workbook and returns the name of the
	 * backup used, or null when no backup exists.
	 */
	public String restoreLatest() {
		List<String> backups = listBackups();
		if (backups.isEmpty()) {
			return null;
		}
		String latest = backups.get(0);
		Path source = backupDir().resolve(latest);
		try {
			Files.copy(source, excelPath(), StandardCopyOption.REPLACE_EXISTING);
			return latest;
		} catch (IOException e) {
			throw new ExcelAccessException("Failed to restore backup: " + source, e);
		}
	}

	private Path excelPath() {
		return Paths.get(excelProperties.path());
	}

	private Path backupDir() {
		Path parent = excelPath().toAbsolutePath().getParent();
		return parent.resolve("backups");
	}

	/**
	 * Two adjustments within the same second would otherwise collide and the first backup
	 * would be silently overwritten, so disambiguate with a counter.
	 */
	private Path uniqueTarget(Path backupDir, LocalDateTime now) {
		String stamp = TIMESTAMP.format(now);
		Path candidate = backupDir.resolve(PREFIX + stamp + SUFFIX);
		int counter = 2;
		while (Files.exists(candidate)) {
			candidate = backupDir.resolve(PREFIX + stamp + "-" + counter + SUFFIX);
			counter++;
		}
		return candidate;
	}
}
