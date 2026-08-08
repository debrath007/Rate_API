package com.example.aprapi.dto;

import jakarta.validation.constraints.NotNull;

public record AdjustRateRequest(@NotNull Double percentage) {
}
