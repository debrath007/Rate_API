package com.example.aprapi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AprRecord {

	public static final String BASIS_VARIABLE = "VARIABLE";

	/** 0-based POI row index in the sheet, used to write updates back to the correct row. */
	private int rowIndex;
	private String accountId;
	private String rateType;
	/** Names the case this row exists to exercise, e.g. SCRA_AT_CAP. */
	private String scenario;
	/** VARIABLE rows track an index; FIXED rows do not move when the index does. */
	private String rateBasis;
	/** Benchmark rate as a percentage number; null on fixed-rate rows. */
	private Double index;
	/** Issuer spread over the index; null on fixed-rate rows. */
	private Double margin;
	/** Normal APR as a percentage number, e.g. 5.99 for 5.99%. */
	private double rate;
	/** Lowest APR the agreement permits, however far the index falls. */
	private Double floorRate;
	/** Highest APR the agreement permits. */
	private Double ceilingRate;
	/** null when no override applies. May list several codes, comma separated. */
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

	public boolean isVariable() {
		return BASIS_VARIABLE.equalsIgnoreCase(rateBasis);
	}

	public boolean hasOverride() {
		return overrideCode != null && !overrideCode.isBlank() && overrideRate != null;
	}
}
