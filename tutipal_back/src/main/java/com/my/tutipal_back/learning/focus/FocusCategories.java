package com.my.tutipal_back.learning.focus;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PT-90 exe name -> model category (the one table, A3). Based on the training table of the focus model
 * (devdesk system/categories.py) plus study tools a student uses in class (tutipal, office, pdf, notepad), which are
 * counted as "ide" (work tools). Unknown names and null are "other". Training table names the API can never
 * receive are left out ("league of legends", "winamax poker" with spaces, "notepad++" with '+'): the exe rule is
 * ^[a-z0-9._-]{1,64}\.exe$, so such programs arrive as null and count as "other" (known limit).
 */
public final class FocusCategories {

    /** The six categories the model was trained on, in model order. */
    public static final List<String> CATEGORIES = List.of("ide", "browser", "terminal", "game", "chat", "other");

    private static final Map<String, String> BY_STEM = new HashMap<>();

    static {
        put("ide", "code", "devenv", "idea64", "pycharm64", "eclipse", "codeblocks", "sublime_text",
                "gedit", "texniccenter", "gummi", "wxmaxima", "sqlitebrowser", "gns3", "androidemulator", "wireshark",
                // study tools used in class
                "tutipal", "hwp", "winword", "excel", "powerpnt", "onenote", "acrobat", "acrord32", "notepad");
        put("browser", "chrome", "firefox", "msedge", "opera", "brave", "chromium-browser", "acwebbrowser", "whale");
        put("terminal", "gnome-terminal-server", "tilix", "konsole", "cmd", "powershell", "pwsh", "windowsterminal",
                "putty", "wezterm-gui", "openconsole");
        put("game", "csgo", "leagueclientux", "leagueclient", "steam", "pokerstars", "pokerstarsbr", "tslgame", "epicgameslauncher", "lostark");
        put("chat", "skype", "skypeforlinux", "telegram", "whatsapp", "thunderbird", "ts3client_win64", "slack",
                "kakaotalk", "discord");
    }

    private FocusCategories() {
    }

    private static void put(String category, String... stems) {
        for (String stem : stems) {
            BY_STEM.put(stem, category);
        }
    }

    /** Every exe stem in the table (tests check that each one can pass the API's exe name rule). */
    static Set<String> stems() {
        return Set.copyOf(BY_STEM.keySet());
    }

    public static String categorize(String exeName) {
        if (exeName == null) {
            return "other";
        }
        String stem = exeName.strip().toLowerCase(Locale.ROOT);
        if (stem.endsWith(".exe")) {
            stem = stem.substring(0, stem.length() - 4);
        }
        return BY_STEM.getOrDefault(stem, "other");
    }
}
