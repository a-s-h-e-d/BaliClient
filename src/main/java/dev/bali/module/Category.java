package dev.bali.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    PLAYER("Player"),
    MISC("Misc"),
    CLIENT("Client");

    public final String title;

    Category(String title) { this.title = title; }
}
