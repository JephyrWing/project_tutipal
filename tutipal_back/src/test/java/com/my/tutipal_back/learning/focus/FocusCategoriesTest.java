package com.my.tutipal_back.learning.focus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** PT-90: the server maps an exe name to one of the six model categories (one table, A3). */
class FocusCategoriesTest {

    @ParameterizedTest
    @CsvSource({
            "chrome.exe, browser",
            "msedge.exe, browser",
            "whale.exe, browser",
            "code.exe, ide",
            "idea64.exe, ide",
            "tutipal.exe, ide",
            "hwp.exe, ide",
            "winword.exe, ide",
            "excel.exe, ide",
            "powerpnt.exe, ide",
            "onenote.exe, ide",
            "acrobat.exe, ide",
            "acrord32.exe, ide",
            "notepad.exe, ide",
            "windowsterminal.exe, terminal",
            "cmd.exe, terminal",
            "powershell.exe, terminal",
            "steam.exe, game",
            "leagueclient.exe, game",
            "kakaotalk.exe, chat",
            "discord.exe, chat",
            "unknown.exe, other",
            "explorer.exe, other",
    })
    void mapsKnownExeNames(String exe, String category) {
        assertThat(FocusCategories.categorize(exe)).isEqualTo(category);
    }

    @Test
    void nullIsOther() {
        assertThat(FocusCategories.categorize(null)).isEqualTo("other");
    }

    @Test
    void categoriesAreTheSixModelCategories() {
        assertThat(FocusCategories.CATEGORIES)
                .containsExactly("ide", "browser", "terminal", "game", "chat", "other");
    }

    @Test
    void everyTableEntryCanPassTheApiExeNameRule() {
        assertThat(FocusCategories.stems()).allSatisfy(stem ->
                assertThat(stem + ".exe").matches("^[a-z0-9._-]{1,64}\\.exe$"));
    }
}
