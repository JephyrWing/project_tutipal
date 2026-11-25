package com.my.tutipal_back.common.auth.controller;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.common.config.DevLogin;

@RestController
public class CsrfController {
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final boolean devLogin;
    public CsrfController(ObjectProvider<ClientRegistrationRepository> registrations,
            Environment env) {
        this.registrations=registrations;
        this.devLogin=DevLogin.enabled(env);
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
