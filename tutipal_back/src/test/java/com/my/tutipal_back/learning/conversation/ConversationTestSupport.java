package com.my.tutipal_back.learning.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.mockito.stubbing.Answer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.learning.tutor.service.TutorService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** PT-106 shared test helpers: login, standalone MockMvc, SSE parsing, and a scripted TutorService mock. */
public final class ConversationTestSupport {

    public static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private ConversationTestSupport() {
    }

    public static void login(long userId) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", String.valueOf(userId)), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
    }

    public static void logout() {
        SecurityContextHolder.clearContext();
    }

    public static MockMvc mvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** One SSE event: name + raw data. */
    public record SseEvent(String name, String data) {
        public JsonNode json() {
            return JSON.readTree(data);
        }
    }

    public static List<SseEvent> events(String body) {
        List<SseEvent> out = new ArrayList<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).replaceFirst("^ ", ""));
                }
            }
            if (name != null) {
                out.add(new SseEvent(name, data.toString()));
            }
        }
        return out;
    }

    public static List<String> eventNames(String body) {
        return events(body).stream().map(SseEvent::name).toList();
    }

    public static SseEvent event(String body, String name) {
        return events(body).stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " event in " + body));
    }

    /** What a mocked tutor.chat does with the request: push deltas, block, throw ... */
    @FunctionalInterface
    public interface TurnScript {
        void run(ChatRequest request, Consumer<String> onDelta) throws Exception;
    }

    /**
     * Stubs every TutorService.chat overload (5, 6 and 7 arguments; onDelta is always argument 4) so the test does
     * not depend on which overload the conversation turn service picks.
     */
    public static void scriptTutor(TutorService tutor, TurnScript script) {
        Answer<Void> answer = inv -> {
            Consumer<String> onDelta = inv.getArgument(4);
            script.run(inv.getArgument(1), onDelta);
            return null;
        };
        doAnswer(answer).when(tutor).chat(any(), any(), any(), any(), any());
        doAnswer(answer).when(tutor).chat(any(), any(), any(), any(), any(), any());
        doAnswer(answer).when(tutor).chat(any(), any(), any(), any(), any(), any(), any());
    }

    /** Every ChatRequest given to tutor.chat (any overload), in call order. */
    public static List<ChatRequest> chatRequests(TutorService tutor) {
        return mockingDetails(tutor).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("chat"))
                .map(i -> (ChatRequest) i.getArgument(1))
                .toList();
    }

    /** Every ChatRequest given to tutor.openChat, in call order. */
    public static List<ChatRequest> openedRequests(TutorService tutor) {
        return mockingDetails(tutor).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("openChat"))
                .map(i -> (ChatRequest) i.getArgument(1))
                .toList();
    }
}
