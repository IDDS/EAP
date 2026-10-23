package com.eap.platform.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InputController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void malformedQueryParametersAreClientErrors() throws Exception {
        mvc.perform(get("/input").param("limit", "not-a-number"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/input"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/input").param("limit", "10").param("state", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonDoesNotExposeBodyOrReportServerFailure() throws Exception {
        mvc.perform(post("/input").contentType(MediaType.APPLICATION_JSON).content("{private-payload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-payload"))));
    }

    @Test
    void editConflictsReturn409AndActionableMessage() throws Exception {
        mvc.perform(post("/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("请重新加载流程"));
    }

    @RestController
    static class InputController {
        enum State { OPEN, CLOSED }
        record Payload(int count) {}

        @PostMapping("/conflict")
        void conflict() { throw new ConflictException("请重新加载流程"); }

        @GetMapping("/input")
        int query(@RequestParam int limit, @RequestParam(required = false) State state) { return limit; }

        @PostMapping("/input")
        int body(@RequestBody Payload payload) { return payload.count(); }
    }
}
