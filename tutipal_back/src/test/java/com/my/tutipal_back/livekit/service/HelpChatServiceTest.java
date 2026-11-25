package com.my.tutipal_back.livekit.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.livekit.dto.HelpChatDtos.*;
import com.my.tutipal_back.livekit.repository.HelpChatStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HelpChatServiceTest {
    HelpChatStore store;
    SessionEvents events;
    HelpChatService service;
    UUID clientId=UUID.randomUUID();
    Message message(String text) { return new Message("1","7","1","1",clientId,text,Instant.EPOCH); }
    @BeforeEach void prepare() {
        store=mock(HelpChatStore.class);events=mock(SessionEvents.class);service=new HelpChatService(store,events);
        when(store.lock(7L)).thenReturn(new HelpChatStore.Context(9L,1L,2L,"ACCEPTED",false));
        when(store.role(9L,1L)).thenReturn(Optional.of("STUDENT"));
        when(store.role(9L,2L)).thenReturn(Optional.of("TEACHER"));
    }
    @Test void newMessageNotifiesOnlyTwoParticipantsWithoutText() {
        var saved=message("개별 질문");when(store.insert(7L,1L,clientId,"개별 질문")).thenReturn(saved);
        assertThat(service.send(1,7,new Send(clientId,"개별 질문"))).isEqualTo(saved);
        verify(events).toUser(1L,"helpChat",7L);verify(events).toUser(2L,"helpChat",7L);verifyNoMoreInteractions(events);
    }
    @Test void duplicateDoesNotAllocateOrNotifyAgain() {
        when(store.previous(7,1,clientId)).thenReturn(Optional.of(message("질문")));
        assertThat(service.send(1,7,new Send(clientId,"질문"))).isEqualTo(message("질문"));
        verify(store,never()).insert(anyLong(),anyLong(),any(),any());verifyNoInteractions(events);
    }
    @Test void reusedClientIdWithDifferentTextIsConflict() {
        when(store.previous(7,1,clientId)).thenReturn(Optional.of(message("원래 질문")));
        assertStatus(HttpStatus.CONFLICT,()->service.send(1,7,new Send(clientId,"변경 질문")));verifyNoInteractions(events);
    }
    @Test void thirdPartyTeacherCannotReadOrSend() {
        when(store.role(9,3)).thenReturn(Optional.of("TEACHER"));
        assertStatus(HttpStatus.NOT_FOUND,()->service.list(3,7,0,100));
        assertStatus(HttpStatus.NOT_FOUND,()->service.send(3,7,new Send(clientId,"질문")));
        verify(store,never()).previous(anyLong(),anyLong(),any());verifyNoInteractions(events);
    }
    @Test void expiredMembershipCannotRead() {
        when(store.role(9,1)).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND,()->service.list(1,7,0,100));verify(store,never()).after(anyLong(),anyLong(),anyInt());
    }
    @Test void pendingDeclinedDoneAndClosedRejectDuplicateRequestsToo() {
        for(String state:List.of("PENDING","DECLINED","DONE")) {
            when(store.lock(7)).thenReturn(new HelpChatStore.Context(9,1,2L,state,false));
            assertStatus(HttpStatus.GONE,()->service.send(1,7,new Send(clientId,"질문")));
        }
        when(store.lock(7)).thenReturn(new HelpChatStore.Context(9,1,2L,"ACCEPTED",true));
        assertStatus(HttpStatus.GONE,()->service.list(1,7,0,100));verify(store,never()).previous(anyLong(),anyLong(),any());
    }
    @Test void oldAcceptActorIsNeverGuessed() {
        when(store.lock(7)).thenReturn(new HelpChatStore.Context(9,1,null,"ACCEPTED",false));
        assertStatus(HttpStatus.GONE,()->service.list(1,7,0,100));assertStatus(HttpStatus.NOT_FOUND,()->service.list(2,7,0,100));
    }
    @Test void missingOtherParticipantClosesAccess() {
        when(store.role(9,2)).thenReturn(Optional.empty());assertStatus(HttpStatus.GONE,()->service.list(1,7,0,100));
    }
    @Test void unicodeLengthAndWhitespaceAreValidatedWithoutRewritingText() {
        assertStatus(HttpStatus.BAD_REQUEST,()->service.send(1,7,new Send(clientId," \n\t ")));
        assertStatus(HttpStatus.BAD_REQUEST,()->service.send(1,7,new Send(clientId,"\u00a0\t\u3000")));
        assertStatus(HttpStatus.BAD_REQUEST,()->service.send(1,7,new Send(clientId,"😀".repeat(1001))));
        String exact="😀".repeat(1000);when(store.insert(7,1,clientId,exact)).thenReturn(message(exact));
        assertThat(service.send(1,7,new Send(clientId,exact)).text()).isEqualTo(exact);
    }
    @Test void pageCursorIsLastReturnedSequenceAndBoundsAreChecked() {
        Message a=message("a"),b=new Message("2","7","9007199254740993","2",UUID.randomUUID(),"b",Instant.EPOCH);
        when(store.after(7,0,2)).thenReturn(List.of(a,b));
        var page=service.list(1,7,0,1);assertThat(page.items()).containsExactly(a);assertThat(page.hasMore()).isTrue();assertThat(page.nextCursor()).isEqualTo("1");
        assertStatus(HttpStatus.BAD_REQUEST,()->service.list(1,7,-1,100));assertStatus(HttpStatus.BAD_REQUEST,()->service.list(1,7,0,101));
    }
    private void assertStatus(HttpStatus status,Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(status));
    }
}
