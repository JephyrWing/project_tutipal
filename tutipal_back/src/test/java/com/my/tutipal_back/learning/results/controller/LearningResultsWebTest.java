package com.my.tutipal_back.learning.results.controller;

import jakarta.servlet.Filter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.my.tutipal_back.common.auth.service.AppLoginSuccessHandler;
import com.my.tutipal_back.common.config.SecurityConfig;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.results.dto.LearningResultsPage.Item;
import com.my.tutipal_back.learning.results.repository.LearningResultsStore;
import com.my.tutipal_back.learning.results.service.LearningResultsService;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.service.SocialOAuth2UserService;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(LearningResultsWebTest.Config.class)
class LearningResultsWebTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class,LearningResultsController.class,LearningResultsService.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean LearningResultsStore store() { return mock(LearningResultsStore.class); }
        @Bean SocialOAuth2UserService oauth() { return mock(SocialOAuth2UserService.class); }
        @Bean AppLoginSuccessHandler loginSuccess() { return mock(AppLoginSuccessHandler.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired LearningResultsStore store;
    MockMvc mvc;
    @BeforeEach void setup() {
        reset(store);
        mvc=MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    MockHttpSession login() {
        User user=new User("google","results-owner","본인");
        ReflectionTestUtils.setField(user,"id",7L);
        var principal=SocialOAuth2UserService.principalOf(user);
        var auth=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());
        var session=new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new SecurityContextImpl(auth));
        return session;
    }
    @Test void anonymousIsRejectedBeforeQuery() throws Exception {
        mvc.perform(get("/api/learning-results")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }
    @Test void principalCannotBeSpoofedAndOnlyAllowListedFieldsAreReturned() throws Exception {
        when(store.count(7L,null,"ALL")).thenReturn(21L);
        when(store.list(7L,null,"ALL",20,0L)).thenReturn(List.of(new Item(1,2,3,"수업","수학","CHOICE",
            "질문",List.of("가","나"),0,null,Instant.parse("2026-11-25T00:00:00Z"),"CORRECT")));
        mvc.perform(get("/api/learning-results?userId=99").session(login()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(21))
            .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items[0].choiceIndex").value(0))
            .andExpect(jsonPath("$.items[0].verdict").value("CORRECT"))
            .andExpect(jsonPath("$.items[0].answerIndex").doesNotExist())
            .andExpect(jsonPath("$.items[0].modelAnswer").doesNotExist())
            .andExpect(jsonPath("$.items[0].feedback").doesNotExist())
            .andExpect(jsonPath("$.items[0].userId").doesNotExist());
        verify(store).count(7L,null,"ALL"); verify(store).list(7L,null,"ALL",20,0L); verify(store).sessions(7L);
        verifyNoMoreInteractions(store);
    }
    @Test void invalidFiltersAndPaginationAreRejected() throws Exception {
        for(String query:List.of("page=-1","size=0","size=101","page=abc","page=2147483648",
                "sessionId=0","sessionId=-1","sessionId=abc","verdict=SECRET")) {
            mvc.perform(get("/api/learning-results?"+query).session(login())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }
    @Test void filtersAreBoundAndLargePageOffsetDoesNotOverflow() throws Exception {
        mvc.perform(get("/api/learning-results?page=2147483647&size=100&sessionId=3&verdict=PENDING").session(login()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        verify(store).count(7L,3L,"PENDING");
        verify(store).list(7L,3L,"PENDING",100,214748364700L); verify(store).sessions(7L);
    }
}
