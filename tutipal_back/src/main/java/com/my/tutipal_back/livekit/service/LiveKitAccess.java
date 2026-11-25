package com.my.tutipal_back.livekit.service;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import com.my.tutipal_back.common.config.SessionTimeouts;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.dto.ScreenShareDtos.JoinInfo;

/** 임의 입장권은 특정 로그인 세션과 방 권한에만 묶인다. 재연결마다 권한을 다시 확인한다. */
@Service
public class LiveKitAccess {
    public record Access(Long userId, boolean help, Long target, String sessionId, HttpSession session, long expires) {}
    private final ConcurrentHashMap<String, Access> entries = new ConcurrentHashMap<>();
    private final ScreenShareService shares;
    private final LiveKitAdmin admin;
    private final Clock clock;
    private final Duration absoluteTimeout;
    @Autowired
    public LiveKitAccess(ScreenShareService shares,LiveKitAdmin admin,
            @Value("${app.session.absolute-timeout:12h}") Duration absoluteTimeout) {
        this(shares,admin,Clock.systemUTC(),SessionTimeouts.requireValidAbsolute(absoluteTimeout));
    }
    LiveKitAccess(ScreenShareService shares,LiveKitAdmin admin,Clock clock,Duration absoluteTimeout) {
        this.shares=shares;this.admin=admin;this.clock=clock;this.absoluteTimeout=absoluteTimeout;
    }
    public JoinInfo issue(Long user, boolean help, Long target, HttpSession session) {
        if(!admin.ready())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"실시간 서버를 준비하고 있어요");
        JoinInfo info=help ? shares.helpToken(user,target) : shares.classToken(user,target);
        long now=clock.millis();
        entries.entrySet().removeIf(e->e.getValue().expires()<now || !sessionValid(e.getValue()));
        if(entries.size()>=10000) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"접속 요청이 많아요");
        String key=UUID.randomUUID().toString();
        entries.put(key,new Access(user,help,target,session.getId(),session,now+absoluteTimeout.toMillis()));
        return new JoinInfo("/livekit/"+key,"session-bound",info.room(),info.identity());
    }
    public Access resolve(String key, HttpSession session) {
        Access a=entries.get(key);
        if(a==null || session==null || !a.sessionId().equals(session.getId()) || !sessionValid(a)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,"로그인이 만료됐어요. 다시 로그인해 주세요");
        }
        upstream(a); // 종료된 방/도움 요청도 거절한다.
        return a;
    }
    public JoinInfo upstream(Access a) {
        if(!sessionValid(a)) throw new ApiException(HttpStatus.UNAUTHORIZED,"로그인이 만료됐어요");
        return a.help() ? shares.helpToken(a.userId(),a.target()) : shares.classToken(a.userId(),a.target());
    }
    private boolean sessionValid(Access a) {
        try {
            long now=clock.millis();
            if(a.expires()<now || now-a.session().getCreationTime()>absoluteTimeout.toMillis()
                    || now-a.session().getLastAccessedTime()>a.session().getMaxInactiveInterval()*1000L) return false;
            Object c=a.session().getAttribute("SPRING_SECURITY_CONTEXT");
            return c instanceof SecurityContext ctx && ctx.getAuthentication()!=null
                && ctx.getAuthentication().isAuthenticated() && String.valueOf(a.userId()).equals(ctx.getAuthentication().getName());
        } catch(IllegalStateException e) { return false; }
    }
}
