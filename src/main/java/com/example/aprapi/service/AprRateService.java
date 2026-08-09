package com.example.aprapi.service;

import com.example.aprapi.backup.BackupService;
import com.example.aprapi.backup.WorkbookLock;
import com.example.aprapi.dto.AccountRatesResponse;
import com.example.aprapi.dto.AdjustRateResponse;
import com.example.aprapi.dto.RateTypeRateDto;
import com.example.aprapi.exception.AccountNotFoundException;
import com.example.aprapi.model.AprRecord;
import com.example.aprapi.repository.ExcelAprRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AprRateService {

	private final ExcelAprRepository repository;
	private final BackupService backupService;
	private final WorkbookLock workbookLock;

	public AprRateService(ExcelAprRepository repository, BackupService backupService, WorkbookLock workbookLock) {
		this.repository = repository;
		this.backupService = backupService;
		this.workbookLock = workbookLock;
	}

	public AccountRatesResponse getRatesForAccount(String accountId) {
		List<RateTypeRateDto> rates = repository.findAll().stream()
				.filter(record -> record.getAccountId().equals(accountId))
				.map(record -> new RateTypeRateDto(
						record.getRateType(), record.getScenario(), round2(record.effectiveRate())))
				.toList();

		if (rates.isEmpty()) {
			throw new AccountNotFoundException(accountId);
		}
		return new AccountRatesResponse(accountId, rates);
	}

	/**
	 * Moves the benchmark index by the given number of points and reprices the portfolio,
	 * as a prime-rate movement would.
	 *
	 * <p>Variable-rate rows are repriced from their own index and margin; fixed-rate rows do
	 * not track the index and are left alone. Takes a backup first so the movement can be
	 * rolled back, and holds the workbook lock so a concurrent adjust or restore cannot
	 * interleave against the same file.
	 */
	public AdjustRateResponse adjustAllRates(double indexChange) {
		return workbookLock.withLock(() -> {
			String backupFile = backupService.createBackup();

			List<AprRecord> repriced = new ArrayList<>();
			for (AprRecord record : repository.findAll()) {
				if (!record.isVariable() || record.getIndex() == null || record.getMargin() == null) {
					continue;
				}

				double newIndex = round2(record.getIndex() + indexChange);
				record.setIndex(newIndex);
				record.setRate(round2(newIndex + record.getMargin()));

				if (record.getOverrideRate() != null) {
					record.setOverrideRate(round2(record.getOverrideRate() + indexChange));
				}

				repriced.add(record);
			}

			int rowsUpdated = repository.updateRates(repriced);
			return new AdjustRateResponse("success", rowsUpdated, backupFile);
		});
	}

	private double round2(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
