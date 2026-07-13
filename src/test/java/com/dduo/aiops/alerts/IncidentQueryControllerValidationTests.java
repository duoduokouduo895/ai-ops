package com.dduo.aiops.alerts;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IncidentQueryControllerValidationTests {

    @Test
    void rejectsOversizedPagesAsBadRequestsBeforeQueryingTheDatabase() throws Exception {
        var jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        var mockMvc = MockMvcBuilders.standaloneSetup(new IncidentQueryController(jdbcTemplate)).build();

        mockMvc.perform(get("/api/v1/incidents").param("size", "101"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jdbcTemplate);
    }
}
