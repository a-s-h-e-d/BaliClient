package dev.bali.manager;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public final class FriendManager {
    private final Set<String> friends = Collections.synchronizedSet(new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

    public boolean add(String name) { return friends.add(name); }
    public boolean remove(String name) { return friends.remove(name); }
    public boolean isFriend(String name) { return friends.contains(name); }

    public boolean isFriend(Entity e) {
        return e instanceof PlayerEntity p && friends.contains(p.getGameProfile().name());
    }

    public Set<String> all() { return friends; }
    public void clear() { friends.clear(); }
}
