package com.my.tutipal_back.learning.review.dto;

import java.util.List;
import com.my.tutipal_back.learning.review.repository.ReviewStore.Item;

/** 본인 복습 기록의 페이지. 임베딩과 소유자 정보는 응답하지 않는다. */
public record ReviewHistoryPage(List<Item> items, long totalCount, int page, int size, long totalPages) {
}
