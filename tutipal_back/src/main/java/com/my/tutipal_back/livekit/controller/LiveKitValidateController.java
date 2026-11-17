package com.my.tutipal_back.livekit.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.my.tutipal_back.livekit.service.LiveKitAccess;
import com.my.tutipal_back.livekit.service.LiveKitProxy;

@RestController
public class LiveKitValidateController {
    private final LiveKitAccess access; private final LiveKitProxy proxy;
    public LiveKitValidateController(LiveKitAccess access,LiveKitProxy proxy) { this.access=access;this.proxy=proxy; }
    @GetMapping("/livekit/{key}/rtc/validate")
    public ResponseEntity<String> validate(@PathVariable String key,HttpServletRequest request)throws Exception {
        var a=access.resolve(key,request.getSession(false));
        URI original=URI.create(request.getRequestURL()+ (request.getQueryString()==null?"":"?"+request.getQueryString()));
        var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
            HttpRequest.newBuilder(proxy.upstreamUri(original,a,false,true)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).header("Cache-Control","no-store").body(response.body());
    }
}
