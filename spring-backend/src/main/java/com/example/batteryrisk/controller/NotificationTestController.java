package com.example.batteryrisk.controller;

import com.example.batteryrisk.dto.ApiResponse;
import com.example.batteryrisk.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// =====================================================================
// [임시 · F10 메일 검증용(B)] 일일 WARNING 다이제스트 수동 트리거.
//
// LLM 없이 "메일이 실제로 Gmail로 나가는지"만 확인하려고 둔 개발용 엔드포인트다.
// 08:00 크론을 기다리지 않고 sendDailyWarningDigest()를 즉시 1회 실행한다.
// procurement_risk_assessments에 WARNING 행을 SQL로 하나 넣어두고 이걸 호출하면
// notification_log(EMAIL_DIGEST)에 발송 결과가 남고 실제 메일이 수신자에게 간다.
//
// ── 롤백 방법 ────────────────────────────────────────────────────────
//   이 파일을 삭제하거나, 아래 @RestController 한 줄을 주석 처리하면 엔드포인트가
//   사라진다(다른 코드에 의존성이 없어 그것만으로 완전 원복된다).
//
//   [2026-08-07] F10 메일 발송·역할 라벨 검증 완료 → 비활성화(주석 처리)함.
//   다시 켜려면 아래 @RestController 주석을 해제하고 `docker compose up -d --build spring`.
// =====================================================================
//@RestController
@RequestMapping("/api/v1/notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationTestController {

    private final NotificationService notificationService;

    public NotificationTestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(
            summary = "[임시] 일일 WARNING 다이제스트 즉시 발송",
            description = "08:00 크론을 기다리지 않고 최근 24h 합성 WARNING 평가를 모아 1통 발송한다. "
                    + "F10 메일 발송 경로(SMTP) 검증용 임시 엔드포인트로, 검증이 끝나면 이 컨트롤러를 제거한다.")
    @PostMapping("/digest/run")
    public ApiResponse<String> runDigest() {
        notificationService.sendDailyWarningDigest();
        return ApiResponse.ok("digest triggered — notification_log와 받은편지함을 확인하세요.");
    }
}
