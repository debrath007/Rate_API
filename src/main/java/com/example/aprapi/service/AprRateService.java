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
				.map(record -> new RateTypeRateDto(record.getRateType(), round2(record.effectiveRate())))
				.toList();

		if (rates.isEmpty()) {
			throw new AccountNotFoundException(accountId);
		}
		return new AccountRatesResponse(accountId, rates);
	}

	/**
	 * Applies a percentage increase/decrease to every row's effective rate (the lower of Rate and
	 * OverrideRate), writing the new value back into whichever column held the effective rate.
	 * Takes a backup first so the change can be rolled back, and holds the workbook lock so a
	 * concurrent adjust or restore cannot interleave against the same file.
	 */
	public AdjustRateResponse adjustAllRates(double percentage) {
		return workbookLock.withLock(() -> {
			String backupFile = backupService.createBackup();

			List<AprRecord> records = repository.findAll();
			double factor = 1 + (percentage / 100.0);

			for (AprRecord record : records) {
				double newEffectiveRate = round2(record.effectiveRate() * factor);
				if (record.effectiveRateIsOverride()) {
					record.setOverrideRate(newEffectiveRate);
				} else {
					record.setRate(newEffectiveRate);
				}
			}

			int rowsUpdated = repository.updateEffectiveRates(records);
			return new AdjustRateResponse("success", rowsUpdated, backupFile);
		});
	}

	private double round2(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}
