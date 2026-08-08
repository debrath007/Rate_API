package com.example.aprapi.exception;

import com.example.aprapi.compliance.ComplianceCheckException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(HttpStatus.BAD_REQUEST, message));
	}

	@ExceptionHandler(ExcelAccessException.class)
	public ResponseEntity<Map<String, Object>> handleExcelAccess(ExcelAccessException ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage()));
	}

	@ExceptionHandler(ComplianceCheckException.class)
	public ResponseEntity<Map<String, Object>> handleComplianceCheck(ComplianceCheckException ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage()));
	}

	private Map<String, Object> errorBody(HttpStatus status, String message) {
		return Map.of(
				"timestamp", Instant.now().toString(),
				"status", status.value(),
				"error", status.getReasonPhrase(),
				"message", message
		);
	}
}
