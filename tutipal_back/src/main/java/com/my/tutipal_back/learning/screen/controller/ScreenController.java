package com.my.tutipal_back.learning.screen.controller;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.screen.dto.ScreenRequest;
import com.my.tutipal_back.learning.screen.service.ScreenService;

@RestController
@RequestMapping("/api/screen")
public class ScreenController {

    private final ScreenService service;

    public ScreenController(ScreenService service) {
        this.service = service;
    }

    /** 화면 요약(question 없음) · 화면 질문. 응답 {text}. */
    @PostMapping
    public Map<String, String> describe(@Valid @RequestBody ScreenRequest request) {
        return Map.of("text", service.describe(request));
    }
}
