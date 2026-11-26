package com.my.tutipal_back.livekit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LiveKit 입장권: HS256 서명, 60초 수명, 화면 공유 외 발행·데이터 채널 금지. */
class LiveKitTokensTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long!!";
    private static final long NOW = 1_800_000_000L;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LiveKitTokens tokens = new LiveKitTokens("api-key", SECRET, json,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private JsonNode verifiedClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[2]).as("서명").isEqualTo(expected);
        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asString()).isEqualTo("HS256");
        return json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    @Test
    void screenSharerMayPublishOnlyScreenShareForSixtySeconds() throws Exception {
        JsonNode claims = verifiedClaims(tokens.mint("user-7", "학생", new LiveKitTokens.Grant("class-3", true, true, false)));

        assertThat(claims.get("iss").asString()).isEqualTo("api-key");
        assertThat(claims.get("sub").asString()).isEqualTo("user-7");
        assertThat(claims.get("nbf").asLong()).isEqualTo(NOW - 5);
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW + 60);
        JsonNode video = claims.get("video");
        assertThat(video.get("room").asString()).isEqualTo("class-3");
        assertThat(video.get("roomJoin").asBoolean()).isTrue();
        assertThat(video.get("canPublish").asBoolean()).isTrue();
        assertThat(video.get("canPublishSources")).hasSize(1);
        assertThat(video.get("canPublishSources").get(0).asString()).isEqualTo("screen_share");
        assertThat(video.get("canSubscribe").asBoolean()).isTrue();
        assertThat(video.get("canPublishData").asBoolean()).isFalse();
        assertThat(video.has("roomAdmin")).isFalse();
    }

    @Test
    void viewerCannotPublishAnythingAndDataChannelStaysClosedEvenIfRequested() throws Exception {
        JsonNode video = verifiedClaims(tokens.mint("user-8", "선생님", new LiveKitTokens.Grant("help-4", false, true, true)))
                .get("video");

        assertThat(video.get("canPublish").asBoolean()).isFalse();
        assertThat(video.get("canPublishSources")).isEmpty();
        assertThat(video.get("canPublishData").asBoolean()).as("Grant.canPublishData 는 무시").isFalse();
    }

    @Test
    void adminTokensAreScopedToOneRoomOrRoomListing() throws Exception {
        JsonNode room = verifiedClaims(tokens.adminToken("class-3", false));
        assertThat(room.has("sub")).isFalse();
        assertThat(room.get("exp").asLong()).isEqualTo(NOW + 60);
        assertThat(room.get("video").get("room").asString()).isEqualTo("class-3");
        assertThat(room.get("video").get("roomAdmin").asBoolean()).isTrue();
        assertThat(room.get("video").has("roomList")).isFalse();

        JsonNode list = verifiedClaims(tokens.adminToken(null, true)).get("video");
        assertThat(list.get("roomList").asBoolean()).isTrue();
        assertThat(list.has("roomAdmin")).isFalse();
    }

    @Test
    void tokenSignedWithAnotherSecretDoesNotVerify() throws Exception {
        LiveKitTokens other = new LiveKitTokens("api-key", "another-secret-at-least-32-bytes!!", json,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        String forged = other.mint("user-7", "학생", new LiveKitTokens.Grant("class-3", true, true, false));
        String genuine = tokens.mint("user-7", "학생", new LiveKitTokens.Grant("class-3", true, true, false));

        assertThat(forged.substring(0, forged.lastIndexOf('.'))).isEqualTo(genuine.substring(0, genuine.lastIndexOf('.')));
        assertThat(forged.substring(forged.lastIndexOf('.'))).isNotEqualTo(genuine.substring(genuine.lastIndexOf('.')));
    }
}
