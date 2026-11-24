package com.my.tutipal_back.common.realtime;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 알림·판서 구독도 로그인 세션이 사라지면 최대 5초 안에 닫는다. */
@Component
public class SessionWebSocketGuard implements HandshakeInterceptor, WebSocketHandlerDecoratorFactory {
    private final ConcurrentHashMap<String,WebSocketSession> sockets=new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"stomp-session-check");t.setDaemon(true);return t;
    });
    public SessionWebSocketGuard() {
        monitor.scheduleWithFixedDelay(()->sockets.forEach((id,s)-> { if(!valid(s))close(s); }),5,5,TimeUnit.SECONDS);
    }
    @Override public boolean beforeHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler handler,Map<String,Object> attrs) {
        if(!(req instanceof ServletServerHttpRequest servlet))return false;
        var session=servlet.getServletRequest().getSession(false);
        if(session==null)return false;
        attrs.put("httpSession",session);attrs.put("httpSessionId",session.getId());
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Exception e) {}
    @Override public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession s)throws Exception {
                sockets.put(s.getId(),s); if(!valid(s)){close(s);return;} super.afterConnectionEstablished(s);
            }
            @Override public void handleMessage(WebSocketSession s,WebSocketMessage<?> m)throws Exception {
                if(!valid(s)){close(s);return;} super.handleMessage(s,m);
            }
            @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status)throws Exception {
                sockets.remove(s.getId());super.afterConnectionClosed(s,status);
            }
        };
    }

    Principal subscriber(String socketId) {
        WebSocketSession socket = socketId == null ? null : sockets.get(socketId);
        return socket != null && valid(socket) ? socket.getPrincipal() : null;
    }

    private boolean valid(WebSocketSession socket) {
        try {
            if(!(socket.getAttributes().get("httpSession") instanceof HttpSession session) || socket.getPrincipal()==null)return false;
            if(!session.getId().equals(socket.getAttributes().get("httpSessionId")))return false;
            long now=System.currentTimeMillis();
            if(now-session.getCreationTime()>12*3600_000L || now-session.getLastAccessedTime()>session.getMaxInactiveInterval()*1000L)return false;
            Object context=session.getAttribute("SPRING_SECURITY_CONTEXT");
            return context instanceof SecurityContext c && c.getAuthentication()!=null && c.getAuthentication().isAuthenticated()
                && c.getAuthentication().getName().equals(socket.getPrincipal().getName());
        } catch(IllegalStateException e){return false;}
    }
    private void close(WebSocketSession session) {
        sockets.remove(session.getId());try{session.close(CloseStatus.POLICY_VIOLATION);}catch(Exception ignored){}
    }
    @PreDestroy public void stop(){monitor.shutdownNow();sockets.values().forEach(this::close);}
}
