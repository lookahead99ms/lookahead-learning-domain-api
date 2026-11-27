package com.lookahead.domain.security.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

/** Only non-browser JSON maintenance calls reach the internal test-account routes. */
class TestAccountOperatorFilterTest {
    final TestAccountOperatorFilter filter = new TestAccountOperatorFilter();

    MockHttpServletResponse through(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test void aJsonPostAndAPlainGetGoOn() throws Exception {
        var post = new MockHttpServletRequest("POST", "/internal/v1/test-accounts/seed"); post.setContentType("application/json; charset=UTF-8");
        var chain = new MockFilterChain();
        assertThat(through(post, chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(post);
        var get = new MockHttpServletRequest("GET", "/internal/v1/test-accounts/csrf");
        var second = new MockFilterChain();
        through(get, second);
        assertThat(second.getRequest()).isSameAs(get);
    }

    @Test void browserLikeRequestsAndNonJsonPostsAreAnswered403() throws Exception {
        var origin = new MockHttpServletRequest("POST", "/internal/v1/test-accounts/seed"); origin.setContentType("application/json"); origin.addHeader("Origin", "https://example.test");
        var fetch = new MockHttpServletRequest("GET", "/internal/v1/test-accounts/csrf"); fetch.addHeader("Sec-Fetch-Site", "same-origin");
        var form = new MockHttpServletRequest("POST", "/internal/v1/test-accounts/seed"); form.setContentType("application/x-www-form-urlencoded");
        var bare = new MockHttpServletRequest("POST", "/internal/v1/test-accounts/seed");
        for (var request : new MockHttpServletRequest[] {origin, fetch, form, bare}) {
            var chain = new MockFilterChain();
            var response = through(request, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(chain.getRequest()).isNull();
        }
    }
}
