package com.my.tutipal_back.livekit.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import com.my.tutipal_back.livekit.dto.HelpChatDtos.*;
import com.my.tutipal_back.livekit.service.HelpChatService;

@RestController
@RequestMapping("/api/help/{helpId}/messages")
public class HelpChatController {
    private final HelpChatService service;
    public HelpChatController(HelpChatService service) { this.service=service; }
    @PostMapping
    public Message send(@AuthenticationPrincipal OAuth2User me,@PathVariable long helpId,@Valid @RequestBody Send body) {
        return service.send(Long.parseLong(me.getName()),helpId,body);
    }
    @GetMapping
    public Page list(@AuthenticationPrincipal OAuth2User me,@PathVariable long helpId,
                     @RequestParam(defaultValue="0") long afterSequence,@RequestParam(defaultValue="100") int limit) {
        return service.list(Long.parseLong(me.getName()),helpId,afterSequence,limit);
    }
}
