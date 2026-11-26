package com.my.tutipal_back.sessions.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.sessions.dto.ChatSettings;
import com.my.tutipal_back.sessions.dto.CreateSessionRequest;
import com.my.tutipal_back.sessions.dto.ParticipantResponse;
import com.my.tutipal_back.sessions.dto.SessionResponse;
import com.my.tutipal_back.sessions.dto.UpdateSettingsRequest;
import com.my.tutipal_back.sessions.entity.SessionParticipant;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.entity.StudySession;
import com.my.tutipal_back.sessions.repository.SessionParticipantRepository;
import com.my.tutipal_back.sessions.repository.StudySessionRepository;
import com.my.tutipal_back.learning.tutor.TopicPrompt;
import com.my.tutipal_back.users.service.UserService;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.Presence;
import com.my.tutipal_back.common.realtime.SessionEvents;

/**
 * 단체 학습 세션. 권한은 계정 역할이 아니라 "이 세션의 참가자/선생님인가" 로 여기서 확인한다.
 * 참가자가 아니면 세션이 있는지도 알려주지 않으려고 404 로 답한다.
 */
@Service
@Transactional(readOnly = true)
public class StudySessionService {

    private static final int CODE_ATTEMPTS = 10;

    private final StudySessionRepository sessions;
    private final SessionParticipantRepository participants;
    private final UserService userService;
    private final SessionEvents events;
    private final Presence presence;
    private final ApplicationEventPublisher publisher;
    private final Clock clock = Clock.systemUTC();

    /**
     * 참가자가 나가거나 내보내졌을 때 같은 트랜잭션 안에서 알리는 이벤트. 화면공유(ScreenShareService)가 받아 그 학생의
     * 열린 도움 요청을 닫는다 (ScreenShareService 가 이 서비스를 쓰므로 직접 부르면 순환 의존이 됨).
     */
    public record ParticipantRemoved(Long sessionId, Long userId) {
    }

    public StudySessionService(StudySessionRepository sessions, SessionParticipantRepository participants,
                               UserService userService, SessionEvents events, Presence presence,
                               ApplicationEventPublisher publisher) {
        this.sessions = sessions;
        this.participants = participants;
        this.userService = userService;
        this.events = events;
        this.presence = presence;
        this.publisher = publisher;
    }

    /** 세션을 연 사람이 그 세션의 TEACHER. */
    @Transactional
    public SessionResponse create(Long userId, CreateSessionRequest request) {
        userService.lockActiveAccount(userId);   // first lock: serialized with withdrawal
        String topic = TopicPrompt.subject(request.topic());
        StudySession session = sessions.save(new StudySession(request.title().trim(), topic, newCode(), userId,
                request.aiRetentionDays()));
        participants.save(new SessionParticipant(session.getId(), userId, SessionRole.TEACHER));
        return SessionResponse.of(session, SessionRole.TEACHER, 1);
    }

    /**
     * 초대 코드로 참여 → STUDENT. 이미 참가자면 원래 역할 그대로 (선생님이 코드를 넣어도 선생님).
     * 외부 참여 요청의 계정별 실패 제한은 InviteCodeJoinService에서 적용한다.
     * 종료와 같은 수업 행을 잠근 상태에서 활성 여부를 확인하고 참가를 저장한다.
     */
    @Transactional
    public SessionResponse join(Long userId, String code) {
        userService.lockActiveAccount(userId);   // before the class row lock (same order as withdrawal)
        StudySession session = sessions.findByInviteCode(InviteCodes.normalize(code))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "코드에 맞는 방이 없어요"));
        if (session.isEnded()) {
            throw new ApiException(HttpStatus.GONE, "이미 닫힌 방이에요");
        }
        SessionParticipant me = participants.findBySessionIdAndUserId(session.getId(), userId)
                .orElseGet(() -> {
                    events.toTeacher(session.getId(), "joined", userId);
                    return participants.save(new SessionParticipant(session.getId(), userId, SessionRole.STUDENT));
                });
        return response(session, me.getRole());
    }

    /** 내가 참가 중인(끝나지 않은) 세션: 즐겨찾기 먼저, 그다음 최근 것부터. */
    public List<SessionResponse> mySessions(Long userId) {
        Map<Long, SessionParticipant> mine = participants.findByUserId(userId).stream()
                .collect(Collectors.toMap(SessionParticipant::getSessionId, p -> p));
        return sessions.findAllById(mine.keySet()).stream()
                .filter(s -> !s.isEnded())
                .sorted(Comparator.comparing((StudySession s) -> !mine.get(s.getId()).isPinned())
                        .thenComparing(StudySession::getId, Comparator.reverseOrder()))
                .map(s -> response(s, mine.get(s.getId()).getRole()).withPinned(mine.get(s.getId()).isPinned()))
                .toList();
    }

    /** 홈 목록 즐겨찾기 켜고 끄기 (내 참가 정보에만 저장, 참가자가 아니면 404). */
    @Transactional
    public void pin(Long userId, Long sessionId, boolean pinned) {
        requireParticipant(sessionId, userId).setPinned(pinned);
    }

    public SessionResponse get(Long userId, Long sessionId) {
        SessionParticipant me = requireParticipant(sessionId, userId);
        return response(findSession(sessionId), me.getRole());
    }

    /** 목업 20: 선생님만. */
    public List<ParticipantResponse> participants(Long userId, Long sessionId) {
        requireTeacher(sessionId, userId);
        List<SessionParticipant> list = participants.findBySessionIdOrderByIdAsc(sessionId);
        Map<Long, String> names = userService.namesOf(list.stream().map(SessionParticipant::getUserId).toList());
        Set<Long> online = presence.onlineUsers(sessionId);
        return list.stream()
                .map(p -> new ParticipantResponse(p.getUserId(), names.getOrDefault(p.getUserId(), "(알 수 없음)"),
                        p.getRole(), p.getCreatedAt(), online.contains(p.getUserId())))
                .toList();
    }

    /** 목업 21: 선생님 설정 → 학생 챗봇에 다음 질문부터 적용. */
    @Transactional
    public SessionResponse updateSettings(Long userId, Long sessionId, UpdateSettingsRequest request) {
        requireTeacher(sessionId, userId);
        StudySession session = requireActive(sessionId);
        session.updateSettings(TopicPrompt.subject(request.topic()), request.difficulty(),
                request.hideAnswers(), request.preferMaterials());
        events.toSession(sessionId, "settings", sessionId);
        return response(session, SessionRole.TEACHER);
    }

    /**
     * PT-13 결정 A: 수업 AI 대화 보관 일수 변경 (선생님만: 비참가자 404, 학생 403). 끝난 수업에서도 바꿀 수 있다:
     * 보관 기간은 종료 시각부터 세므로, 줄이면 이미 지난 대화는 다음 정리 작업(1시간마다)에서 지워진다.
     */
    @Transactional
    public SessionResponse updateAiRetention(Long userId, Long sessionId, int aiRetentionDays) {
        requireTeacher(sessionId, userId);
        StudySession session = findSession(sessionId);
        session.changeAiRetentionDays(aiRetentionDays);
        return response(session, SessionRole.TEACHER);
    }

    /** 세션 종료: 새 참여 · 세션 챗봇이 막힌다. 여러 번 불러도 같음. */
    @Transactional
    public SessionResponse end(Long userId, Long sessionId) {
        lockForHelp(sessionId);
        requireTeacher(sessionId, userId);
        StudySession session = findSession(sessionId);
        if (!session.isEnded()) {
            session.end(clock.instant());
            events.toSession(sessionId, "ended", sessionId);
        }
        return response(session, SessionRole.TEACHER);
    }

    /**
     * FR-SES-07 학생이 방에서 나가기. 참가 기록이 지워지므로 이후 질문 · 챗봇 · 화면공유 요청이 404 로 막히고,
     * 이미 연결된 LiveKit 은 LiveKitProxy 주기 검사가 끊는다. 선생님은 나갈 수 없다 (방 닫기를 쓴다).
     */
    @Transactional
    public void leave(Long userId, Long sessionId) {
        lockForHelp(sessionId);
        SessionParticipant me = requireParticipant(sessionId, userId);
        if (me.isTeacher()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "선생님은 나갈 수 없어요. 방 닫기를 써 주세요");
        }
        remove(me);
    }

    /** FR-SES-07 선생님이 학생 내보내기. 내보낸 학생 화면에는 removed 알림. 다시 코드로 참여하는 것은 막지 않는다. */
    @Transactional
    public void kick(Long teacherId, Long sessionId, Long studentId) {
        lockForHelp(sessionId);
        requireTeacher(sessionId, teacherId);
        SessionParticipant target = participants.findBySessionIdAndUserId(sessionId, studentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "참가자를 찾을 수 없어요"));
        if (target.isTeacher()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "선생님은 내보낼 수 없어요");
        }
        remove(target);
        events.toUser(studentId, "removed", sessionId);
    }

    private void remove(SessionParticipant p) {
        participants.delete(p);
        publisher.publishEvent(new ParticipantRemoved(p.getSessionId(), p.getUserId()));
        events.toTeacher(p.getSessionId(), "left", p.getUserId());
    }

    /** tutor 도메인용: 참가자이고 진행 중인 세션이면 선생님 챗봇 설정. */
    public ChatSettings chatSettings(Long userId, Long sessionId) {
        requireParticipant(sessionId, userId);
        StudySession s = requireActive(sessionId);
        return new ChatSettings(s.getTopic(), s.getDifficulty(), s.isHideAnswers(), s.isPreferMaterials());
    }

    /** STOMP 구독 검사용: 진행 중인 세션의 참가자면 역할, 아니면 empty. */
    public Optional<SessionRole> roleOf(Long userId, Long sessionId) {
        return participantRole(userId, sessionId)
                .filter(role -> sessions.findById(sessionId).filter(s -> !s.isEnded()).isPresent());
    }

    /** 알림 전달 시 참가자 권한을 확인한다. 남은 참가자는 방이 닫혔다는 알림을 받을 수 있다. */
    public Optional<SessionRole> participantRole(Long userId, Long sessionId) {
        return participants.findBySessionIdAndUserId(sessionId, userId)
                .map(SessionParticipant::getRole);
    }

    /** 다른 도메인(quiz)용: 진행 중인 세션의 참가자인지 확인하고 역할을 돌려준다 (아니면 404 · 410). */
    public SessionRole requireActiveRole(Long userId, Long sessionId) {
        SessionRole role = requireParticipant(sessionId, userId).getRole();
        requireActive(sessionId);
        return role;
    }

    /** Serialize help/chat writes with close, leave and kick. Check access separately. */
    @Transactional
    public void lockForHelp(Long sessionId) {
        sessions.lockById(sessionId);
    }

    /** 다른 도메인(quiz)용: 그 세션의 선생님인지 확인 (아니면 404 · 403). */
    public void requireTeacherOf(Long userId, Long sessionId) {
        requireTeacher(sessionId, userId);
    }

    /** 이해도 질문 응답률 분모: 학생 수. */
    public long studentCount(Long sessionId) {
        return participants.countBySessionIdAndRole(sessionId, SessionRole.STUDENT);
    }

    private SessionParticipant requireParticipant(Long sessionId, Long userId) {
        return participants.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요"));
    }

    private void requireTeacher(Long sessionId, Long userId) {
        if (!requireParticipant(sessionId, userId).isTeacher()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 할 수 있어요");
        }
    }

    private StudySession findSession(Long sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요"));
    }

    private StudySession requireActive(Long sessionId) {
        StudySession session = findSession(sessionId);
        if (session.isEnded()) {
            throw new ApiException(HttpStatus.GONE, "이미 닫힌 방이에요");
        }
        return session;
    }

    private SessionResponse response(StudySession session, SessionRole myRole) {
        return SessionResponse.of(session, myRole, participants.countBySessionId(session.getId()));
    }

    private String newCode() {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = InviteCodes.generate();
            if (!sessions.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "초대 코드를 만들지 못했어요. 다시 시도해 주세요");
    }
}
