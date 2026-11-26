package com.my.tutipal_back.common.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.common.auth.service.AppLoginService;
import com.my.tutipal_back.common.auth.service.SessionLogin;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.users.entity.User;
import com.my.tutipal_back.users.service.UserService;

/**
 * Codex P1-2 (PT-55): an app login code issued before a withdrawal and exchanged after it gets 401. Either
 * UserService.getUser already sees the withdrawn account, or SessionLogin's re-check inside the per-account lock
 * refuses it; both reach the client as 401 through GlobalExceptionHandler.
 */
class AppLoginExchangeWithdrawnTest {

    private static final String BODY = "{\"code\":\"c\",\"verifier\":\"v\"}";

    private final AppLoginService appLogin = mock(AppLoginService.class);
    private final SessionLogin sessionLogin = mock(SessionLogin.class);
    private final UserService users = mock(UserService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AppLoginController(appLogin, sessionLogin, users, new MockEnvironment()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void withdrawalCommittedBetweenGetUserAndLoginIs401() throws Exception {
        User user = new User("kakao", "k-7", "이름");
        ReflectionTestUtils.setField(user, "id", 7L);
        when(appLogin.exchange("c", "v")).thenReturn(7L);
        when(users.getUser(7L)).thenReturn(user);
        doThrow(new ApiException(HttpStatus.UNAUTHORIZED, "탈퇴했거나 없는 계정이에요"))
                .when(sessionLogin).login(any(), any(), any());

        mvc.perform(post("/api/auth/app/exchange").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.name").doesNotExist());
    }
}
