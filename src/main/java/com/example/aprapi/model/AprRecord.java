package com.example.aprapi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AprRecord {

	/** 0-based POI row index in the sheet, used to write updates back to the correct row. */
	private int rowIndex;
	private String accountId;
	private String rateType;
	/** Normal APR as a percentage number, e.g. 5.99 for 5.99%. */
	private double rate;
	private double balance;
	/** null when no override applies. */
	private String overrideCode;
	/** Override APR as a percentage number; null when overrideCode is blank. */
	private Double overrideRate;

	public double effectiveRate() {
		if (overrideCode == null || overrideCode.isBlank() || overrideRate == null) {
			return rate;
		}
		return Math.min(rate, overrideRate);
	}

	/** True when the effective rate came from the OverrideRate column rather than Rate. */
	public boolean effectiveRateIsOverride() {
		return overrideCode != null && !overrideCode.isBlank() && overrideRate != null && overrideRate < rate;
	}
}
