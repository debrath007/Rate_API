package com.example.aprapi.compliance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Mirrors the JSON emitted by {@code check_compliance.py}. Unknown properties are ignored
 * so the script can add fields without breaking this reader — notably {@code compliant},
 * which is derived here rather than bound from the payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceReport(
		String status,
		String checkedAt,
		int rowsChecked,
		int accountsChecked,
		int criticalCount,
		int highCount,
		/** Version of the rule file that produced this report. */
		String rulesVersion,
		/** Ids of the rules that actually ran; a disabled rule will not appear. */
		List<String> rulesApplied,
		List<ComplianceViolation> violations) {

	public boolean isCompliant() {
		return violations.isEmpty();
	}
}
