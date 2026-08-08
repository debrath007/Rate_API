package com.example.aprapi.exception;

public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(String accountId) {
		super("No rate records found for accountId: " + accountId);
	}
}
