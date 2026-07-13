package com.dduo.aiops.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReadApiAuthenticationInterceptorTests {

    private final ReadApiAuthenticationInterceptor interceptor = new ReadApiAuthenticationInterceptor(
            new ReadApiAuthenticator("reader", "0123456789abcdef0123456789abcdef"));

    @Test
    void rejectsUnauthenticatedGetWithoutWritingABody() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/incidents");
        var response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"aidiag-read-api\"");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void acceptsAuthenticatedGetAndDoesNotInterceptStageFourPost() throws Exception {
        var get = new MockHttpServletRequest("GET", "/api/v1/incidents");
        get.addHeader(HttpHeaders.AUTHORIZATION, basic("reader", "0123456789abcdef0123456789abcdef"));

        assertThat(interceptor.preHandle(get, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/incidents/id/ai-diagnosis-preview"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
