package com.my.tutipal_back.learning.review.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.my.tutipal_back.learning.review.repository.ReviewStore.Item;
import com.my.tutipal_back.learning.review.service.ReviewService;
import com.my.tutipal_back.learning.review.service.ReviewService.Observed;
import com.my.tutipal_back.learning.review.dto.ReviewHistoryPage;

/** 복습: 자기 기록만 (모든 요청이 로그인한 본인 id 기준). */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    /** summary 가 없으면 같은 화면을 계속 보는 중 */
    public record ObserveRequest(@Size(max = 2000) String summary, int seconds) {
    }

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/observe")
    public Observed observe(@AuthenticationPrincipal OAuth2User me, @Valid @RequestBody ObserveRequest req) {
        return service.observe(uid(me), req.summary(), req.seconds());
    }

    @GetMapping("/items")
    public List<Item> list(@AuthenticationPrincipal OAuth2User me) {
        return service.list(uid(me));
    }

    @GetMapping("/history")
    public ReviewHistoryPage history(@AuthenticationPrincipal OAuth2User me,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.history(uid(me), page, size);
    }

    @GetMapping("/items/{id}")
    public Item get(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        return service.get(uid(me), id);
    }

    @DeleteMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(@AuthenticationPrincipal OAuth2User me) {
        service.deleteAll(uid(me));
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal OAuth2User me, @PathVariable Long id) {
        service.delete(uid(me), id);
    }

    private static Long uid(OAuth2User me) {
        return Long.valueOf(me.getName());
    }
}
