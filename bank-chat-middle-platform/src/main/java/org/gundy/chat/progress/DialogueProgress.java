package org.gundy.chat.progress;

import java.util.function.Consumer;

public final class DialogueProgress {
    private static final ThreadLocal<Consumer<Event>> LISTENER = new ThreadLocal<Consumer<Event>>();

    private DialogueProgress() {}

    public static void install(Consumer<Event> listener) { LISTENER.set(listener); }
    public static void clear() { LISTENER.remove(); }

    public static void report(String code, String title, String detail) {
        Consumer<Event> listener = LISTENER.get();
        if (listener != null) listener.accept(new Event(code, title, detail));
    }

    public static class Event {
        private final String code;
        private final String title;
        private final String detail;

        public Event(String code, String title, String detail) {
            this.code = code; this.title = title; this.detail = detail;
        }
        public String getCode() { return code; }
        public String getTitle() { return title; }
        public String getDetail() { return detail; }
    }
}
