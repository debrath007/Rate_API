package com.example.aprapi.requirements;

/** The requirement catalogue could not be read. Distinct from it being empty. */
public class RequirementsUnavailableException extends RuntimeException {

	public RequirementsUnavailableException(String message) {
		super(message);
	}
}
