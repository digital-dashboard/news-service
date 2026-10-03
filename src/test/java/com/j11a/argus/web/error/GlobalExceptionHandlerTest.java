package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.ProbeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String PROBE = "/news/v2/probe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownRouteReturnsProblemJsonWithNotFoundCode() throws Exception {
        mockMvc.perform(get("/news/v2/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("urn:argus:problem:not-found"));
    }

    @Test
    void unknownRouteOutsideApiPrefixIsAlsoProblemJson() throws Exception {
        mockMvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void methodNotAllowedHasCodeAndAllowHeader() throws Exception {
        mockMvc.perform(put(PROBE).header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void apiExceptionMapsCodeStatusAndDetail() throws Exception {
        mockMvc.perform(get(PROBE + "/api-error"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("no such feed"))
                .andExpect(jsonPath("$.type").value("urn:argus:problem:feed-not-found"));
    }

    @Test
    void validationFailureListsFieldErrors() throws Exception {
        mockMvc.perform(post(PROBE).header(AdminKeys.HEADER, AdminKeys.VALID).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void unreadableBodyIsBadRequest() throws Exception {
        mockMvc.perform(post(PROBE).header(AdminKeys.HEADER, AdminKeys.VALID).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unsupportedMediaTypeHasItsOwnCode() throws Exception {
        mockMvc.perform(post(PROBE).header(AdminKeys.HEADER, AdminKeys.VALID).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void typeMismatchIsValidationFailedNamingTheParameter() throws Exception {
        mockMvc.perform(get(PROBE + "/count").param("value", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
    }

    @Test
    void missingParameterIsValidationFailedNamingTheParameter() throws Exception {
        mockMvc.perform(get(PROBE + "/count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingTheMessage(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(get(PROBE + "/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(ProbeController.SECRET_DETAIL).doesNotContain("stackTrace");
        assertThat(output).contains(ProbeController.SECRET_DETAIL);
    }

    @Test
    void apiExceptionPropertiesAreCopiedOntoTheProblem() throws Exception {
        mockMvc.perform(get(PROBE + "/api-error-props"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEED_URL_CONFLICT"))
                .andExpect(jsonPath("$.existingFeedId").value(7));
    }

    @Test
    void unknownEnumValueIsValidationFailedNamingTheField() throws Exception {
        mockMvc.perform(post(PROBE + "/mode").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"WARP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("mode"));
    }

    @Test
    void anElementOfTheWrongTypeInsideAListHasNoFieldNameSoItIsBadRequest() throws Exception {
        mockMvc.perform(post(PROBE + "/list").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("[{}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void bodyOfTheWrongShapeWithoutAFieldIsBadRequest() throws Exception {
        mockMvc.perform(post(PROBE + "/mode").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void invocationRejectedIsConflictWithPollInProgressCode() throws Exception {
        mockMvc.perform(get(PROBE + "/rejected"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_IN_PROGRESS"))
                .andExpect(jsonPath("$.type").value("urn:argus:problem:poll-in-progress"))
                .andExpect(jsonPath("$.title").value("Poll already in progress"))
                .andExpect(jsonPath("$.detail").value("A poll is already in progress."));
    }
}
