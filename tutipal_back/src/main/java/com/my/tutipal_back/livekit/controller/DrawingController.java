package com.my.tutipal_back.livekit.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.service.ScreenShareService;

/** 판서 좌표는 LiveKit 데이터 채널을 사용하지 않는다. */
@RestController
public class DrawingController {
    private final ScreenShareService shares;
    private final SimpMessagingTemplate broker;
    public DrawingController(ScreenShareService shares, SimpMessagingTemplate broker) { this.shares=shares; this.broker=broker; }
    public record Drawing(Map<String,Object> message) {}
    @PostMapping("/api/help/{id}/draw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void draw(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id, @RequestBody Drawing body) {
        Long target = shares.drawingRecipient(Long.valueOf(me.getName()), id);
        Map<String,Object> m = body.message();
        if (m == null || !valid(m)) throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 판서 데이터입니다");
        broker.convertAndSendToUser(String.valueOf(target), "/queue/draw", Map.of("helpId",id,"message",m));
    }
    private boolean valid(Map<String,Object> m) {
        if (m.size()>6) return false;
        if ("clear".equals(m.get("k"))) return m.size()==1;
        if ("del".equals(m.get("k"))) return m.get("ids") instanceof List<?> ids && ids.size()<=500
                && ids.stream().allMatch(x->x instanceof String s && s.length()<=40);
        if (!"add".equals(m.get("k")) || !(m.get("id") instanceof String id) || id.length()>40
                || !List.of("pen","hl").contains(m.get("tool"))
                || !List.of("#ef4444","#2563eb","#facc15").contains(m.get("c"))) return false;
        return m.get("p") instanceof List<?> points && points.size()<=500 && points.stream().allMatch(p->
            p instanceof List<?> xy && xy.size()==2 && xy.stream().allMatch(v->v instanceof Number n
                && Double.isFinite(n.doubleValue()) && n.doubleValue()>=0 && n.doubleValue()<=1));
    }
}
