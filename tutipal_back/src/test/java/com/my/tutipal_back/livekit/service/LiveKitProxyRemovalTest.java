package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.WebSocketSession;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.livekit.dto.ScreenShareDtos.JoinInfo;

/**
 * 한 계정당 기기 1대: 이전 기기 세션이 끊길 때 LiveKit 참가자 정리(identity u&lt;userId&gt;)가 같은 방에 이미 들어온 새 기기를
 * 내보내지 않는다.
 */
class LiveKitProxyRemovalTest {

    private final LiveKitAccess access = mock(LiveKitAccess.class);
    private final LiveKitAdmin admin = mock(LiveKitAdmin.class);
    private final LiveKitProxy proxy = new LiveKitProxy(access, admin, "http://livekit:7880");

    private final LiveKitAccess.Access oldDevice = new LiveKitAccess.Access(7L, false, 5L, "old", null, Long.MAX_VALUE);
    private final LiveKitAccess.Access newDevice = new LiveKitAccess.Access(7L, false, 5L, "new", null, Long.MAX_VALUE);

    @AfterEach
    void stop() {
        proxy.shutdown();
    }

    private void expired(LiveKitAccess.Access a) {
        when(access.upstream(a)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요"));
    }

    private void valid(LiveKitAccess.Access a, String room) {
        when(access.upstream(a)).thenReturn(new JoinInfo("ws://livekit", "t", room, "u" + a.userId()));
    }

    private static WebSocketSession socket(String room) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        if (room != null) attrs.put("room", room);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    private void runCheck() throws Exception {
        Method check = LiveKitProxy.class.getDeclaredMethod("check");
        check.setAccessible(true);
        check.invoke(proxy);
    }

    @Test
    void expiredOldDeviceDoesNotRemoveTheNewDeviceInTheSameRoom() throws Exception {
        expired(oldDevice);
        valid(newDevice, "session-5-class");
        WebSocketSession oldSocket = socket(null);
        proxy.pairs.put("old", new LiveKitProxy.Pair(oldSocket, socket(null), oldDevice));
        proxy.pairs.put("new", new LiveKitProxy.Pair(socket(null), socket(null), newDevice));

        runCheck();

        verify(oldSocket, timeout(1000)).close(org.mockito.ArgumentMatchers.any());
        verify(admin, after(500).never()).remove(anyString(), anyString());
        assertThat(proxy.pairs).containsOnlyKeys("new");
    }

    @Test
    void expiredDeviceIsRemovedWhenNoOtherLiveConnectionExists() throws Exception {
        expired(oldDevice);
        when(admin.remove("session-5-class", "u7")).thenReturn(true);
        proxy.pairs.put("old", new LiveKitProxy.Pair(socket(null), socket(null), oldDevice));

        runCheck();

        verify(admin, timeout(1000)).remove("session-5-class", "u7");
    }

    @Test
    void onlySameUserAndSameRoomCountAsReplacement() {
        LiveKitAccess.Access otherUser = new LiveKitAccess.Access(8L, false, 5L, "x", null, Long.MAX_VALUE);
        LiveKitAccess.Access otherRoom = new LiveKitAccess.Access(7L, false, 6L, "y", null, Long.MAX_VALUE);
        valid(otherUser, "session-5-class");
        valid(otherRoom, "session-6-class");
        proxy.pairs.put("a", new LiveKitProxy.Pair(socket(null), socket(null), otherUser));
        proxy.pairs.put("b", new LiveKitProxy.Pair(socket(null), socket(null), otherRoom));

        assertThat(proxy.replacedByLiveConnection(new LiveKitProxy.Removal("session-5-class", "u7"))).isFalse();
    }

    @Test
    void helpRoomUsesTheRoomFixedAtConnectAndIgnoresExpiredRelays() {
        LiveKitAccess.Access helpNew = new LiveKitAccess.Access(7L, true, 3L, "new", null, Long.MAX_VALUE);
        LiveKitAccess.Access helpOld = new LiveKitAccess.Access(7L, true, 3L, "old", null, Long.MAX_VALUE);
        valid(helpNew, "help-3");
        expired(helpOld);
        proxy.pairs.put("old", new LiveKitProxy.Pair(socket("help-3"), socket(null), helpOld));
        assertThat(proxy.replacedByLiveConnection(new LiveKitProxy.Removal("help-3", "u7"))).isFalse();

        proxy.pairs.put("new", new LiveKitProxy.Pair(socket("help-3"), socket(null), helpNew));
        assertThat(proxy.replacedByLiveConnection(new LiveKitProxy.Removal("help-3", "u7"))).isTrue();
    }
}
