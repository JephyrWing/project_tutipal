package com.my.tutipal_back.learning.conversation.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.service.ConversationService;

/**
 * PT-106 stored AI conversations (docs/AI_CONVERSATIONS.md). Always scoped to the logged-in user; another user's
 * conversation is 404. Sending a message is {@link ConversationTurnController} (SSE).
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** No sessionId → personal conversations; sessionId → my conversations in that session. */
    @GetMapping
    public ConversationDtos.Page list(@AuthenticationPrincipal OAuth2User me,
                                      @RequestParam(required = false) Long sessionId,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return conversations.list(userId(me), sessionId, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<ConversationDtos.Detail> create(@AuthenticationPrincipal OAuth2User me,
                                                          @Valid @RequestBody ConversationDtos.CreateRequest request) {
        ConversationDtos.Detail created = conversations.create(userId(me), request.topic(), request.sessionId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ConversationDtos.Detail detail(@AuthenticationPrincipal OAuth2User me, @PathVariable long id) {
        return conversations.detail(userId(me), id);
    }

    @GetMapping("/{id}/messages")
    public ConversationDtos.MessagePage messages(@AuthenticationPrincipal OAuth2User me, @PathVariable long id,
                                                 @RequestParam(required = false) Long before,
                                                 @RequestParam(required = false) Integer limit) {
        return conversations.messages(userId(me), id, before, limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal OAuth2User me, @PathVariable long id) {
        conversations.delete(userId(me), id);
        return ResponseEntity.noContent().build();
    }

    /** PT-13 결정 C: {"saved": true|false} → {id, saved, savedAt}. Owner only (404 otherwise). */
    @PutMapping("/{id}/saved")
    public ConversationDtos.SavedState setSaved(@AuthenticationPrincipal OAuth2User me, @PathVariable long id,
                                                @Valid @RequestBody ConversationDtos.SaveRequest request) {
        return conversations.setSaved(userId(me), id, request.saved());
    }

    @PostMapping("/{id}/memories")
    public ResponseEntity<ConversationDtos.MemoryView> saveMemory(@AuthenticationPrincipal OAuth2User me,
                                                                  @PathVariable long id,
                                                                  @Valid @RequestBody ConversationDtos.MemoryRequest request) {
        return ResponseEntity.status(201).body(conversations.saveMemory(userId(me), id, request.text()));
    }

    @DeleteMapping("/{id}/memories/{memoryId}")
    public ResponseEntity<Void> deleteMemory(@AuthenticationPrincipal OAuth2User me, @PathVariable long id,
                                             @PathVariable long memoryId) {
        conversations.deleteMemory(userId(me), id, memoryId);
        return ResponseEntity.noContent().build();
    }

    private static long userId(OAuth2User me) {
        return Long.parseLong(me.getName());
    }
}
