package com.example.batteryrisk.briefing;

import com.example.batteryrisk.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/risks/{riskId}/briefing")
public class BriefingController {

    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    @GetMapping
    public ApiResponse<BriefingResponse> getBriefing(
            @PathVariable long riskId
    ) {
        return ApiResponse.ok(
                briefingService.getBriefing(riskId)
        );
    }
}