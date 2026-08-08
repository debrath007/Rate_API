package com.example.aprapi.repository;

import com.example.aprapi.config.ExcelProperties;
import com.example.aprapi.exception.ExcelAccessException;
import com.example.aprapi.model.AprRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ExcelAprRepository {

	private static final int COL_ACCOUNT_ID = 0;
	private static final int COL_RATE_TYPE = 1;
	private static final int COL_RATE = 2;
	private static final int COL_BALANCE = 3;
	private static final int COL_OVERRIDE_CODE = 4;
	private static final int COL_OVERRIDE_RATE = 5;
	private static final int HEADER_ROW_INDEX = 0;

	private final ExcelProperties excelProperties;

	public ExcelAprRepository(ExcelProperties excelProperties) {
		this.excelProperties = excelProperties;
	}

	public List<AprRecord> findAll() {
		List<AprRecord> records = new ArrayList<>();
		try (InputStream is = new FileInputStream(excelProperties.path());
			 Workbook workbook = WorkbookFactory.create(is)) {
			Sheet sheet = workbook.getSheetAt(0);
			for (int rowIndex = HEADER_ROW_INDEX + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
				Row row = sheet.getRow(rowIndex);
				if (row == null || isBlankCell(row.getCell(COL_ACCOUNT_ID))) {
					continue;
				}
				records.add(toRecord(row));
			}
			return records;
		} catch (IOException e) {
			throw new ExcelAccessException("Failed to read APR excel file: " + excelProperties.path(), e);
		}
	}

	/**
	 * Updates the effective-rate cell (Rate or OverrideRate, per {@link AprRecord#effectiveRateIsOverride()})
	 * for each given record, in a single open/save pass, and returns the number of rows updated.
	 */
	public int updateEffectiveRates(List<AprRecord> updates) {
		if (updates.isEmpty()) {
			return 0;
		}
		File file = new File(excelProperties.path());
		try {
			Workbook workbook;
			// Load fully into memory and close the read handle before opening a write handle,
			// since Windows will not allow the file to be opened for writing while it is still
			// held open for reading.
			try (InputStream is = new FileInputStream(file)) {
				workbook = WorkbookFactory.create(is);
			}
			try (workbook) {
				Sheet sheet = workbook.getSheetAt(0);
				for (AprRecord record : updates) {
					Row row = sheet.getRow(record.getRowIndex());
					if (row == null) {
						continue;
					}
					int targetCol = record.effectiveRateIsOverride() ? COL_OVERRIDE_RATE : COL_RATE;
					double newValue = record.effectiveRateIsOverride() ? record.getOverrideRate() : record.getRate();
					Cell cell = row.getCell(targetCol);
					if (cell == null) {
						cell = row.createCell(targetCol);
					}
					cell.setCellValue(newValue / 100.0);
				}
				try (var fos = new FileOutputStream(file)) {
					workbook.write(fos);
				}
			}
			return updates.size();
		} catch (IOException e) {
			throw new ExcelAccessException("Failed to update APR excel file: " + excelProperties.path(), e);
		}
	}

	private AprRecord toRecord(Row row) {
		String accountId = stringValue(row.getCell(COL_ACCOUNT_ID));
		String rateType = stringValue(row.getCell(COL_RATE_TYPE));
		double rate = numericValue(row.getCell(COL_RATE)) * 100.0;
		double balance = numericValue(row.getCell(COL_BALANCE));
		String overrideCode = stringValue(row.getCell(COL_OVERRIDE_CODE));
		Cell overrideRateCell = row.getCell(COL_OVERRIDE_RATE);
		Double overrideRate = isBlankCell(overrideRateCell) ? null : numericValue(overrideRateCell) * 100.0;
		return new AprRecord(row.getRowNum(), accountId, rateType, rate, balance, overrideCode, overrideRate);
	}

	private boolean isBlankCell(Cell cell) {
		return cell == null || cell.getCellType() == CellType.BLANK
				|| (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
	}

	private String stringValue(Cell cell) {
		if (isBlankCell(cell)) {
			return null;
		}
		return switch (cell.getCellType()) {
			case NUMERIC -> String.valueOf(cell.getNumericCellValue());
			default -> cell.getStringCellValue().trim();
		};
	}

	private double numericValue(Cell cell) {
		if (cell == null) {
			return 0.0;
		}
		return switch (cell.getCellType()) {
			case NUMERIC -> cell.getNumericCellValue();
			case STRING -> Double.parseDouble(cell.getStringCellValue().trim());
			default -> 0.0;
		};
	}
}
