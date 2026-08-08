package com.example.aprapi.dto;

import java.util.List;

public record AccountRatesResponse(String accountId, List<RateTypeRateDto> rates) {
}
