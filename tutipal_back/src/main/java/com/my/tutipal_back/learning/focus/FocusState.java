package com.my.tutipal_back.learning.focus;

/**
 * PT-90 reference focus state shown to the teacher and the student (rule-agreement based, not an accuracy claim).
 * FOCUS / DISTRACTED / BREAK come from the model; UNKNOWN = model server failed; PENDING = not enough minutes yet;
 * NOT_PARTICIPATING = no state row or the last one is older than app.focus.stale-after (never stored).
 */
public enum FocusState {
    FOCUS,
    DISTRACTED,
    BREAK,
    UNKNOWN,
    PENDING,
    NOT_PARTICIPATING
}
