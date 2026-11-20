package dev.bali.event;

import dev.bali.Bali;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal annotation-driven event bus. Listener arrays are copy-on-write, so posting
 * (which happens on the netty thread for incoming packets) never takes a lock.
 */
public final class EventBus {
    private record Listener(Object owner, Method method, MethodHandle handle, int priority) {}

    private static final Listener[] EMPTY = new Listener[0];
    private final Map<Class<?>, Listener[]> listeners = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Method>> methodCache = new ConcurrentHashMap<>();

    public synchronized void subscribe(Object owner) {
        for (Method m : methodsOf(owner.getClass())) {
            Class<?> type = m.getParameterTypes()[0];
            Listener[] current = listeners.getOrDefault(type, EMPTY);
            boolean already = false;
            for (Listener l : current) if (l.owner == owner && l.method.equals(m)) { already = true; break; }
            if (already) continue;
            try {
                MethodHandle h = MethodHandles.lookup().unreflect(m).bindTo(owner);
                List<Listener> list = new ArrayList<>(List.of(current));
                list.add(new Listener(owner, m, h, m.getAnnotation(EventHandler.class).priority()));
                list.sort(Comparator.comparingInt((Listener x) -> x.priority).reversed());
                listeners.put(type, list.toArray(EMPTY));
            } catch (IllegalAccessException e) {
                Bali.LOG.error("Failed to subscribe {}", m, e);
            }
        }
    }

    public synchronized void unsubscribe(Object owner) {
        for (Map.Entry<Class<?>, Listener[]> e : listeners.entrySet()) {
            Listener[] arr = e.getValue();
            boolean found = false;
            for (Listener l : arr) if (l.owner == owner) { found = true; break; }
            if (!found) continue;
            List<Listener> list = new ArrayList<>(arr.length);
            for (Listener l : arr) if (l.owner != owner) list.add(l);
            e.setValue(list.toArray(EMPTY));
        }
    }

    public <T> T post(T event) {
        Listener[] arr = listeners.get(event.getClass());
        if (arr == null) return event;
        for (Listener l : arr) {
            try {
                l.handle.invoke(event);
            } catch (Throwable t) {
                Bali.LOG.error("Error in listener {} for {}", l.owner.getClass().getSimpleName(), event.getClass().getSimpleName(), t);
            }
        }
        return event;
    }

    private List<Method> methodsOf(Class<?> clazz) {
        return methodCache.computeIfAbsent(clazz, c -> {
            List<Method> out = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(EventHandler.class) && m.getParameterCount() == 1) {
                        m.setAccessible(true);
                        out.add(m);
                    }
                }
            }
            return out;
        });
    }
}
