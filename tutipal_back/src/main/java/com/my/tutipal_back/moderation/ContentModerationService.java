package com.my.tutipal_back.moderation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.materials.service.MaterialObjectStore;
import com.my.tutipal_back.moderation.ContentModerationDtos.Action;
import com.my.tutipal_back.moderation.ContentModerationDtos.Item;
import com.my.tutipal_back.moderation.ContentModerationDtos.ItemPage;
import com.my.tutipal_back.moderation.ContentModerationDtos.Kind;
import com.my.tutipal_back.moderation.ContentModerationDtos.VisibilityRequest;
import com.my.tutipal_back.moderation.ContentModerationRepository.LockedItem;
import com.my.tutipal_back.users.entity.Role;
import com.my.tutipal_back.users.service.UserService;

@Service
@Transactional(readOnly = true)
class ContentModerationService {

    private static final int PAGE_SIZE = 30;
    private static final int RECENT_ACTION_LIMIT = 20;

    private final ContentModerationRepository content;
    private final UserService users;
    private final MaterialObjectStore objects;
    private final SessionEvents events;

    ContentModerationService(ContentModerationRepository content, UserService users,
                             MaterialObjectStore objects, SessionEvents events) {
        this.content = content;
        this.users = users;
        this.objects = objects;
        this.events = events;
    }

    public ItemPage list(long actorId, Kind kind, Long before) {
        requireAdmin(actorId);
        List<Item> rows = content.list(kind, before, PAGE_SIZE + 1);
        if (rows.size() <= PAGE_SIZE) {
            return new ItemPage(rows, null);
        }
        List<Item> page = List.copyOf(rows.subList(0, PAGE_SIZE));
        return new ItemPage(page, page.getLast().id());
    }

    public List<Action> history(long actorId, Kind kind, long id) {
        requireAdmin(actorId);
        return content.history(kind, id, RECENT_ACTION_LIMIT);
    }

    @Transactional
    public void setVisibility(long actorId, Kind kind, long id, VisibilityRequest request) {
        users.lockActiveAccount(actorId);
        requireAdmin(actorId);
        String reason = request.reason().strip();
        if (reason.isBlank() || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "조치 사유를 제어문자 없이 입력해 주세요");
        }
        LockedItem locked = content.lock(kind, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "관리할 콘텐츠를 찾을 수 없어요"));
        Item item = locked.item();
        boolean hidden = item.hiddenAt() != null;
        if (request.hidden() == hidden) {
            return;
        }
        if (item.version() != request.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "다른 관리자가 공개 상태를 바꿨어요. 최신 상태를 확인해 주세요");
        }
        if (kind == Kind.MATERIAL && !request.hidden()
                && (locked.originalMissing() || !objects.exists(locked.storageKey()))) {
            throw new ApiException(HttpStatus.CONFLICT, "원본 자료를 복구한 뒤 다시 공개해 주세요");
        }
        content.change(kind, item, actorId, request.hidden(), reason);
        events.toSession(item.sessionId(), kind == Kind.POST ? "posts" : "materials", id);
    }

    private void requireAdmin(long actorId) {
        if (users.getUser(actorId).getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 콘텐츠를 관리할 수 있어요");
        }
    }
}
