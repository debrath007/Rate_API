package com.example.aprapi.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param indexChange points to move the benchmark index by, e.g. 0.5 for a
 *                    half-point prime-rate rise, -0.25 for a quarter-point cut.
 */
public record AdjustRateRequest(@NotNull Double indexChange) {
}
