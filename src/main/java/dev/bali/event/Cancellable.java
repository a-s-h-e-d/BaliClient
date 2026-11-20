package dev.bali.event;

public abstract class Cancellable {
    private boolean cancelled;

    public boolean isCancelled() { return cancelled; }
    public void cancel() { cancelled = true; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
