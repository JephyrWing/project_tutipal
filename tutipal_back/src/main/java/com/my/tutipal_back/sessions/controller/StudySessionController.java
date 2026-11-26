package com.my.tutipal_back.sessions.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.sessions.dto.CreateSessionRequest;
import com.my.tutipal_back.sessions.dto.JoinSessionRequest;
import com.my.tutipal_back.sessions.dto.ParticipantResponse;
import com.my.tutipal_back.sessions.dto.SessionResponse;
import com.my.tutipal_back.sessions.dto.UpdateAiRetentionRequest;
import com.my.tutipal_back.sessions.dto.UpdateSettingsRequest;
import com.my.tutipal_back.sessions.service.InviteCodeJoinService;
import com.my.tutipal_back.sessions.service.InviteCodeJoinService.RetryLater;
import com.my.tutipal_back.sessions.service.StudySessionService;

@RestController
@RequestMapping("/api/sessions")
public class StudySessionController {

    private final StudySessionService service;
    private final InviteCodeJoinService inviteCodeJoin;

    public StudySessionController(StudySessionService service, InviteCodeJoinService inviteCodeJoin) {
        this.service = service;
        this.inviteCodeJoin = inviteCodeJoin;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@AuthenticationPrincipal OAuth2User me, @Valid @RequestBody CreateSessionRequest request) {
        return service.create(userId(me), request);
    }

    @PostMapping("/join")
    public SessionResponse join(@AuthenticationPrincipal OAuth2User me, @Valid @RequestBody JoinSessionRequest request) {
        return inviteCodeJoin.join(userId(me), request.code());
    }

    @ExceptionHandler(RetryLater.class)
    ResponseEntity<Map<String, Object>> retryJoinLater(RetryLater error) {
        return ResponseEntity.status(error.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(error.retryAfterSeconds()))
                .body(Map.of("status", error.getStatus().value(), "message", error.getMessage()));
    }

    @GetMapping
    public List<SessionResponse> mySessions(@AuthenticationPrincipal OAuth2User me) {
        return service.mySessions(userId(me));
    }

    @GetMapping("/{id}")
    public SessionResponse get(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        return service.get(userId(me), id);
    }

    @GetMapping("/{id}/participants")
    public List<ParticipantResponse> participants(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        return service.participants(userId(me), id);
    }

    @PutMapping("/{id}/settings")
    public SessionResponse updateSettings(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id,
                                          @Valid @RequestBody UpdateSettingsRequest request) {
        return service.updateSettings(userId(me), id, request);
    }

    /** PT-13 결정 A: 수업 AI 대화 보관 일수 (선생님만, 끝난 수업도 가능). {"aiRetentionDays": 0..90} */
    @PutMapping("/{id}/ai-retention")
    public SessionResponse updateAiRetention(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id,
                                             @Valid @RequestBody UpdateAiRetentionRequest request) {
        return service.updateAiRetention(userId(me), id, request.aiRetentionDays());
    }

    /** 즐겨찾기: {"pinned": true|false} */
    @PutMapping("/{id}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id, @RequestBody PinRequest request) {
        service.pin(userId(me), id, request.pinned());
    }

    public record PinRequest(boolean pinned) {
    }

    @PostMapping("/{id}/end")
    public SessionResponse end(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        return service.end(userId(me), id);
    }

    /** FR-SES-07 학생이 방에서 나가기 */
    @DeleteMapping("/{id}/participants/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        service.leave(userId(me), id);
    }

    /** FR-SES-07 선생님이 학생 내보내기 */
    @DeleteMapping("/{id}/participants/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id, @PathVariable Long userId) {
        service.kick(userId(me), id, userId);
    }

    private static Long userId(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
