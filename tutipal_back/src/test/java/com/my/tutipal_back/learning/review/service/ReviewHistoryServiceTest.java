package com.my.tutipal_back.learning.review.service;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.review.repository.ReviewStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewHistoryServiceTest {
    ReviewStore store;
    ReviewService service;

    @BeforeEach void setup() {
        store = mock(ReviewStore.class);
        service = new ReviewService(store, mock(AiClient.class));
    }

    @Test void calculatesPagesAndUsesPrincipalForCountAndSlice() {
        when(store.count(7L)).thenReturn(105L);
        when(store.history(7L, 20, 100L)).thenReturn(List.of());
        var result = service.history(7L, 5, 20);
        assertThat(result.totalCount()).isEqualTo(105);
        assertThat(result.totalPages()).isEqualTo(6);
        assertThat(result.page()).isEqualTo(5);
        assertThat(result.size()).isEqualTo(20);
        verify(store).count(7L);
        verify(store).history(7L, 20, 100L);
    }

    @Test void emptyHistoryHasNoPages() {
        when(store.history(7L, 20, 0L)).thenReturn(List.of());
        var result = service.history(7L, 0, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test void rejectsInvalidPageAndSizeBeforeQuerying() {
        for (int[] params : List.of(new int[]{-1, 20}, new int[]{0, 0}, new int[]{0, -1}, new int[]{0, 101})) {
            assertThatThrownBy(() -> service.history(7L, params[0], params[1])).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(store);
    }

    @Test void largePageOffsetDoesNotOverflowInteger() {
        when(store.history(7L, 100, 214748364700L)).thenReturn(List.of());
        service.history(7L, Integer.MAX_VALUE, 100);
        verify(store).history(7L, 100, 214748364700L);
    }
}
