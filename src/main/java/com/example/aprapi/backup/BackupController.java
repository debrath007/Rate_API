package com.example.aprapi.backup;

import com.example.aprapi.dto.BackupListResponse;
import com.example.aprapi.dto.RestoreResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BackupController {

	private final BackupService backupService;
	private final WorkbookLock workbookLock;

	public BackupController(BackupService backupService, WorkbookLock workbookLock) {
		this.backupService = backupService;
		this.workbookLock = workbookLock;
	}

	@GetMapping("/api/backups")
	public BackupListResponse listBackups() {
		return new BackupListResponse(backupService.listBackups());
	}

	@PostMapping("/api/backups/restore")
	public ResponseEntity<RestoreResponse> restoreLatest() {
		String restoredFrom = workbookLock.withLock(backupService::restoreLatest);

		if (restoredFrom == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(new RestoreResponse("error", null, "No backups available to restore"));
		}
		return ResponseEntity.ok(
				new RestoreResponse("success", restoredFrom, "Workbook restored from " + restoredFrom));
	}
}
