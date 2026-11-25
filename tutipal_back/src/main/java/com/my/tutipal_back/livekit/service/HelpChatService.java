package com.my.tutipal_back.livekit.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.livekit.dto.HelpChatDtos.*;
import com.my.tutipal_back.livekit.repository.HelpChatStore;
import com.my.tutipal_back.livekit.repository.HelpChatStore.Context;

@Service
@Transactional
public class HelpChatService {
    public static final int MAX_LENGTH=1000;
    private final HelpChatStore store;
    private final SessionEvents events;
    public HelpChatService(HelpChatStore store,SessionEvents events) { this.store=store;this.events=events; }
    private Context authorize(long userId,long helpId) {
        Context context=store.lock(helpId);
        String role=store.role(context.sessionId(),userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"도움을 찾을 수 없어요"));
        if (!(userId==context.studentId() && role.equals("STUDENT"))
                && !(context.teacherId()!=null && userId==context.teacherId() && role.equals("TEACHER"))) {
            throw new ApiException(HttpStatus.NOT_FOUND,"도움을 찾을 수 없어요");
        }
        if (context.ended() || !context.status().equals("ACCEPTED") || context.teacherId()==null) {
            throw new ApiException(HttpStatus.GONE,"진행 중인 도움이 아니에요");
        }
        if (!store.role(context.sessionId(),context.studentId()).orElse("").equals("STUDENT")
                || !store.role(context.sessionId(),context.teacherId()).orElse("").equals("TEACHER")) {
            throw new ApiException(HttpStatus.GONE,"도움 참여 권한이 끝났어요");
        }
        return context;
    }
    public Message send(long userId,long helpId,Send input) {
        Context context=authorize(userId,helpId);
        if(input==null || input.clientMessageId()==null || input.text()==null || input.text().isBlank()
                || input.text().codePointCount(0,input.text().length())>MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"메시지는 공백만 입력하지 말고 1~1000자로 적어 주세요");
        }
        var previous=store.previous(helpId,userId,input.clientMessageId());
        if(previous.isPresent()) {
            if(!previous.get().text().equals(input.text())) throw new ApiException(HttpStatus.CONFLICT,"같은 전송 번호의 내용이 달라요");
            return previous.get();
        }
        Message message=store.insert(helpId,userId,input.clientMessageId(),input.text());
        events.toUser(context.studentId(),"helpChat",helpId);
        events.toUser(context.teacherId(),"helpChat",helpId);
        return message;
    }
    public Page list(long userId,long helpId,long after,int limit) {
        authorize(userId,helpId);
        if(after<0 || limit<1 || limit>100) throw new ApiException(HttpStatus.BAD_REQUEST,"대화 조회 범위를 확인해 주세요");
        List<Message> found=store.after(helpId,after,limit+1);
        boolean more=found.size()>limit;
        List<Message> items=List.copyOf(found.subList(0,Math.min(limit,found.size())));
        return new Page(items,items.isEmpty()?Long.toString(after):items.getLast().sequence(),more,MAX_LENGTH);
    }
}
