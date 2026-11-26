package com.my.tutipal_back.learning.focus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PT-90 (text check only; the real Postgres run is out of scope today): V23__focus_check.sql creates the three
 * focus tables, every row goes with its user / session through ON DELETE CASCADE (AC-9), one sample per
 * (session, user, minute) (AC-5), and the stored values are limited like the API (AC-3).
 */
class FocusMigrationTest {

    private static final String PATH = "/db/migration/V23__focus_check.sql";
    private List<String> statements;

    @BeforeEach
    void load() throws IOException {
        try (InputStream in = FocusMigrationTest.class.getResourceAsStream(PATH)) {
            assertThat(in).as(PATH + " on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("--[^\n]*", " ")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            statements = Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        }
    }

    private String table(String name) {
        Pattern about = Pattern.compile("^(create table( if not exists)? " + name + "\\b|alter table( only)? "
                + name + "\\b)");
        String joined = String.join(" ; ", statements.stream().filter(s -> about.matcher(s).find()).toList());
        assertThat(joined).as("CREATE TABLE " + name).isNotEmpty();
        return joined;
    }

    private static boolean cascades(String table, String column, String referenced) {
        Pattern inline = Pattern.compile("\\b" + column + "\\b[^,;]*?references " + referenced
                + "\\b[^,;]*?on delete cascade");
        Pattern constraint = Pattern.compile("foreign key \\(" + column + "\\) references " + referenced
                + "\\b[^,;]*?on delete cascade");
        return inline.matcher(table).find() || constraint.matcher(table).find();
    }

    private boolean indexOn(String table, String column) {
        Pattern index = Pattern.compile("create index( if not exists)? \\w+ on " + table + "( using btree)? ?\\( ?"
                + column + " ?\\)");
        return statements.stream().anyMatch(s -> index.matcher(s).find());
    }

    @Test
    void consentsCascadeFromUsersAndStoreVersionAndTime() {
        String t = table("focus_consents");

        assertThat(cascades(t, "user_id", "users")).as("focus_consents.user_id → users CASCADE").isTrue();
        assertThat(t).contains("primary key").contains("version varchar(20) not null")
                .contains("consented_at timestamptz not null");
    }

    @Test
    void samplesCascadeFromSessionsAndUsers() {
        String t = table("focus_samples");

        assertThat(cascades(t, "session_id", "study_sessions")).as("focus_samples.session_id CASCADE").isTrue();
        assertThat(cascades(t, "user_id", "users")).as("focus_samples.user_id CASCADE").isTrue();
    }

    @Test
    void oneSamplePerSessionUserAndMinute() {
        String t = table("focus_samples");

        assertThat(t).containsPattern("unique ?\\( ?session_id, ?user_id, ?minute_at ?\\)");
        assertThat(t).contains("minute_at timestamptz not null");
    }

    @Test
    void sampleValuesAreLimitedLikeTheApi() {
        String t = table("focus_samples");

        assertThat(t).contains("exe_name varchar(68)");
        assertThat(t).contains("idle_sec smallint").contains("switch_count smallint");
        assertThat(t).contains("60").contains("120");
        for (String category : List.of("ide", "browser", "terminal", "game", "chat", "other")) {
            assertThat(t).contains("'" + category + "'");
        }
        assertThat(t).contains("category varchar(10) not null");
    }

    @Test
    void statesCascadeAndKeepOneRowPerStudentAndSession() {
        String t = table("focus_states");

        assertThat(cascades(t, "session_id", "study_sessions")).isTrue();
        assertThat(cascades(t, "user_id", "users")).isTrue();
        assertThat(t).containsPattern("primary key ?\\( ?session_id, ?user_id ?\\)");
        assertThat(t).contains("updated_at timestamptz not null");
        for (String state : List.of("focus", "distracted", "break", "unknown", "pending")) {
            assertThat(t).contains("'" + state + "'");
        }
        assertThat(t).doesNotContain("'not_participating'");
    }

    @Test
    void userDeletesUseAnIndex() {
        assertThat(indexOn("focus_samples", "user_id")).as("INDEX focus_samples(user_id)").isTrue();
        assertThat(indexOn("focus_states", "user_id")).as("INDEX focus_states(user_id)").isTrue();
    }
}
