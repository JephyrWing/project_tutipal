package com.my.tutipal_back.livekit.service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** LiveKit 제어 소켓은 같은 Spring 서버 안에서 세션과 방 권한을 확인한 뒤 중계한다. */
@Configuration
@EnableWebSocket
public class LiveKitProxy implements WebSocketConfigurer {
    private record Pair(WebSocketSession client, WebSocketSession upstream, LiveKitAccess.Access access) {}
    private final LiveKitAccess access;
    private final String internal;
    @Value("${app.allowed-origins:http://127.0.0.1:*,http://localhost:*}") private String[] allowedOrigins;
    private final LiveKitAdmin admin;
    private record Removal(String room,String identity) {}
    private final ConcurrentHashMap<String,Removal> pendingRemovals=new ConcurrentHashMap<>();
    private final java.util.Set<String> removing=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService removalWorker=Executors.newFixedThreadPool(2,r->{
        Thread t=new Thread(r,"livekit-participant-remove");t.setDaemon(true);return t;
    });
    private final ConcurrentHashMap<String,Pair> pairs=new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"livekit-session-check"); t.setDaemon(true); return t;
    });
    public LiveKitProxy(LiveKitAccess access, LiveKitAdmin admin, @Value("${tutipal.livekit.internal-url}") String internal) {
        this.access=access; this.internal=internal;this.admin=admin;
        monitor.scheduleWithFixedDelay(this::check,5,5,TimeUnit.SECONDS);
    }
    public static String grantKey(String path) {
        String[] parts=path.split("/"); return parts.length>=3 ? parts[2] : "";
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new Relay(),"/livekit/*/rtc", "/livekit/*")
            .setAllowedOriginPatterns(allowedOrigins)
            .addInterceptors(new HandshakeInterceptor() {
                @Override public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String,Object> attrs) {
                    if (!(req instanceof ServletServerHttpRequest servlet)) return false;
                    HttpServletRequest request=servlet.getServletRequest();
                    try { attrs.put("access",access.resolve(grantKey(request.getRequestURI()),request.getSession(false))); return true; }
                    catch(RuntimeException e) { res.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED); return false; }
                }
                @Override public void afterHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Exception e) {}
            });
    }
    public URI upstreamUri(URI original, LiveKitAccess.Access a, boolean socket, boolean validate) {
        var builder=UriComponentsBuilder.fromUriString(internal.replaceFirst("^http",socket?"ws":"http"))
            .path(validate?"/rtc/validate":"/rtc");
        var query=UriComponentsBuilder.fromUri(original).build().getQueryParams();
        query.forEach((key,values)-> {
            if (java.util.Set.of("sdk","version","protocol","auto_subscribe","adaptive_stream","reconnect","sid","publish","client_protocol").contains(key))
                for(String value:values) builder.queryParam(key,value);
        });
        builder.queryParam("access_token",access.upstream(a).token());
        return builder.build().encode().toUri();
    }
    private class Relay extends AbstractWebSocketHandler {
        @Override public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
            var downstream=new ConcurrentWebSocketSessionDecorator(raw,10000,1024*1024);
            var a=(LiveKitAccess.Access)raw.getAttributes().get("access");
            raw.getAttributes().put("room",access.upstream(a).room());
            try {
                var upstream=new StandardWebSocketClient().execute(new AbstractWebSocketHandler() {
                    @Override protected void handleTextMessage(WebSocketSession s,TextMessage m)throws Exception { downstream.sendMessage(m); }
                    @Override protected void handleBinaryMessage(WebSocketSession s,BinaryMessage m)throws Exception { downstream.sendMessage(m); }
                    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status)throws Exception { close(raw.getId(),status); }
                    @Override public void handleTransportError(WebSocketSession s,Throwable e) { close(raw.getId(),CloseStatus.SERVER_ERROR); }
                },new WebSocketHttpHeaders(),upstreamUri(raw.getUri(),a,true,false)).get(10,TimeUnit.SECONDS);
                upstream.setBinaryMessageSizeLimit(1024*1024); upstream.setTextMessageSizeLimit(1024*1024);
                raw.setBinaryMessageSizeLimit(1024*1024); raw.setTextMessageSizeLimit(1024*1024);
                pairs.put(raw.getId(),new Pair(downstream,new ConcurrentWebSocketSessionDecorator(upstream,10000,1024*1024),a));
            } catch(Exception e) { downstream.close(CloseStatus.SERVER_ERROR); }
        }
        @Override protected void handleTextMessage(WebSocketSession s,TextMessage m)throws Exception { var p=pairs.get(s.getId()); if(p!=null)p.upstream().sendMessage(m); }
        @Override protected void handleBinaryMessage(WebSocketSession s,BinaryMessage m)throws Exception { var p=pairs.get(s.getId()); if(p!=null)p.upstream().sendMessage(m); }
        @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status) { close(s.getId(),status); }
        @Override public void handleTransportError(WebSocketSession s,Throwable e) { close(s.getId(),CloseStatus.SERVER_ERROR); }
    }
    private void check() {
        pairs.forEach((id,p)-> {
            try { access.upstream(p.access()); }
            catch(RuntimeException e) { revoke(p);close(id,CloseStatus.POLICY_VIOLATION); }
        });
        pendingRemovals.forEach(this::queueRemoval);
    }
    private void revoke(Pair p) {
        String room=p.access().help() ? "" : "session-"+p.access().target()+"-class";
        if(p.access().help()) {
            // 활성 매핑에는 최초 검증에서 확정된 방 이름을 저장한다.
            room=(String)p.client().getAttributes().get("room");
        }
        if(room!=null && !room.isEmpty()) {
            String identity="u"+p.access().userId();
            String key=room+"|"+identity;
            Removal removal=new Removal(room,identity);
            pendingRemovals.put(key,removal);
            queueRemoval(key,removal);
        }
    }
    private void queueRemoval(String key,Removal removal) {
        if(!removing.add(key))return;
        try {
            removalWorker.submit(()-> {
                try { if(admin.remove(removal.room(),removal.identity()))pendingRemovals.remove(key,removal); }
                finally { removing.remove(key); }
            });
        } catch(java.util.concurrent.RejectedExecutionException e) { removing.remove(key); }
    }
    private void close(String id,CloseStatus status) {
        Pair p=pairs.remove(id); if(p==null)return;
        try { access.upstream(p.access()); } catch(RuntimeException invalid) { revoke(p); }
        try { p.client().close(status); } catch(Exception ignored) {}
        try { p.upstream().close(status); } catch(Exception ignored) {}
    }
    @PreDestroy public void shutdown() {
        monitor.shutdownNow();pairs.forEach((id,p)-> { revoke(p);close(id,CloseStatus.GOING_AWAY); });
        removalWorker.shutdown();
        try { removalWorker.awaitTermination(6,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        removalWorker.shutdownNow();
    }
}
