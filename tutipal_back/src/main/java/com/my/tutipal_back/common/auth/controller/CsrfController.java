package com.my.tutipal_back.common.auth.controller;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final boolean devLogin;
    public CsrfController(ObjectProvider<ClientRegistrationRepository> registrations,
            @org.springframework.beans.factory.annotation.Value("${app.dev-login:false}") boolean devLogin) {
        this.registrations=registrations;
        this.devLogin=devLogin;
    }
    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }
    @GetMapping("/api/auth/providers")
    public List<String> providers() {
        if (!(registrations.getIfAvailable() instanceof Iterable<?> clients)) return List.of();
        var result = new java.util.ArrayList<String>();
        for (Object c : clients) result.add(((ClientRegistration)c).getRegistrationId());
        return result;
    }
    @GetMapping("/api/auth/config")
    public Map<String,Object> config() {
        return Map.of("devLogin",devLogin,"providers",providers());
    }
}
