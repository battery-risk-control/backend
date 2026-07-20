package com.example.batteryrisk.dashboard;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class DashboardService {

    public DashboardSummaryResponse getSummary() {
        return new DashboardSummaryResponse(
                17,
                3,
                8,
                6,
                21.4,
                5,
                OffsetDateTime.now()
        );
    }
}