package com.my.tutipal_back.livekit.service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 인증 매핑을 잃으면 미디어 참가자도 제거한다. Spring 재시작 시 전용 LiveKit의 이전 방을 정리한다. */
@Service
public class LiveKitAdmin {
    private final LiveKitTokens tokens; private final JsonMapper json; private final String internal;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1).build();
    private final AtomicBoolean ready=new AtomicBoolean();
    public LiveKitAdmin(LiveKitTokens tokens,JsonMapper json,@Value("${tutipal.livekit.internal-url}")String internal) { this.tokens=tokens;this.json=json;this.internal=internal; }
    public boolean ready() { return ready.get(); }
    private String call(String method,String room,Map<String,?> data,boolean list) throws Exception {
        HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create(internal+"/twirp/livekit.RoomService/"+method))
            .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+tokens.adminToken(room,list))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data))).build(),HttpResponse.BodyHandlers.ofString());
        if(method.equals("RemoveParticipant") && response.statusCode()==404)return "{}";
        if(response.statusCode()<200 || response.statusCode()>=300)throw new IllegalStateException("LiveKit admin unavailable");
        return response.body();
    }
    public boolean remove(String room,String identity) {
        try { call("RemoveParticipant",room,Map.of("room",room,"identity",identity),false); return true; }
        catch(Exception e) { return false; } // 모니터가 재시도한다. 토큰/응답 원문은 기록하지 않는다.
    }
    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        Thread.ofVirtual().name("livekit-reconcile").start(()-> {
            while(!ready.get()) {
                try {
                    var rooms=json.readTree(call("ListRooms","",Map.of(),true)).path("rooms");
                    for(var room:rooms) {
                        String name=room.path("name").asText();
                        if(!name.startsWith("session-"))continue;
                        var participants=json.readTree(call("ListParticipants",name,Map.of("room",name),false)).path("participants");
                        for(var p:participants) if(!remove(name,p.path("identity").asText()))throw new IllegalStateException();
                    }
                    ready.set(true);
                } catch(Exception e) {
                    try { Thread.sleep(5000); } catch(InterruptedException stop) { Thread.currentThread().interrupt();return; }
                }
            }
        });
    }
}
