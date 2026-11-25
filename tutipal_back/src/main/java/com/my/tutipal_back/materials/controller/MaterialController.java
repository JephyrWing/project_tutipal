package com.my.tutipal_back.materials.controller;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.my.tutipal_back.materials.entity.Material;
import com.my.tutipal_back.materials.repository.MaterialRepository.MaterialRow;
import com.my.tutipal_back.materials.repository.MaterialRepository.ReusableMaterial;
import com.my.tutipal_back.materials.service.MaterialService;

@RestController
@RequestMapping("/api")
public class MaterialController {

    private final MaterialService service;

    public MaterialController(MaterialService service) {
        this.service = service;
    }

    @PostMapping(value = "/sessions/{sessionId}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialRow upload(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                              @RequestParam("file") MultipartFile file,
                              @RequestParam(defaultValue = "false") boolean alert) {
        return service.upload(userId(me), sessionId, file, alert);
    }

    @GetMapping("/sessions/{sessionId}/materials/reusable")
    public List<ReusableMaterial> reusable(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return service.reusable(userId(me), sessionId);
    }

    @PostMapping("/sessions/{sessionId}/materials/reuse/{sourceId}")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialRow reuse(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId,
                             @PathVariable Long sourceId, @RequestParam(defaultValue = "false") boolean alert) {
        return service.reuse(userId(me), sessionId, sourceId, alert);
    }

    @GetMapping("/sessions/{sessionId}/materials")
    public List<MaterialRow> list(@AuthenticationPrincipal OAuth2User me, @PathVariable Long sessionId) {
        return service.list(userId(me), sessionId);
    }

    /** 항상 첨부 파일로 내려준다 (브라우저가 열어서 실행하지 않게), 종류는 확장자로 우리가 정함 */
    @GetMapping("/materials/{id}/file")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        Material m = service.download(userId(me), id);
        MediaType type = m.getFilename().toLowerCase().endsWith(".pdf") ? MediaType.APPLICATION_PDF : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(m.getFilename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.downloadData(m));
    }

    @DeleteMapping("/materials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        service.delete(userId(me), id);
    }

    @PostMapping("/materials/{id}/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialRow requestIndex(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        return service.requestIndex(userId(me), id);
    }

    private static Long userId(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
