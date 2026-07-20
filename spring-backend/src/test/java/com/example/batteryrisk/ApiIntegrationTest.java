package com.example.batteryrisk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mockMvc;

    @Test
    void allDummyEndpointsReturnSuccess() throws Exception {
        for (String path : new String[]{
                "/api/v1/dashboard/summary",
                "/api/v1/risks",
                "/api/v1/risks/101",
                "/api/v1/contracts",
                "/api/v1/contracts/501",
                "/api/v1/risks/101/briefing"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Test
    void riskDetailUsesSpecificationShape() throws Exception {
        mockMvc.perform(get("/api/v1/risks/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.sourceName").value("GDELT"))
                .andExpect(jsonPath("$.data.material.materialName").value("Lithium"))
                .andExpect(jsonPath("$.data.supplier.supplierName").value("SQM"))
                .andExpect(jsonPath("$.data.analysis.impactDomain").value("PRODUCTION"))
                .andExpect(jsonPath("$.data.inventory.stockDays").value(12));
    }

    @Test
    void unknownResourceUsesTypedErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/risks/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RISK_NOT_FOUND"));
    }

    @Test
    void invalidEnumUsesCommonErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/risks").param("severity", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void corsAllowsReactDevelopmentOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
