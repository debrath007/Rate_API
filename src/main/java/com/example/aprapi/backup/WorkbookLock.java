package com.example.aprapi.backup;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes every writer of the APR workbook. Adjusting rates and restoring a backup both
 * rewrite the same file, so they must contend for one lock rather than each guarding itself.
 */
@Component
public class WorkbookLock {

	private final ReentrantLock lock = new ReentrantLock();

	public <T> T withLock(Supplier<T> action) {
		lock.lock();
		try {
			return action.get();
		} finally {
			lock.unlock();
		}
	}
}
