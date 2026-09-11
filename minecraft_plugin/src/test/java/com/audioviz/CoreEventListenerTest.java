package com.audioviz;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreEventListenerTest {
    @Test
    void listenerDiscoveryWorksWhenVoiceChatApiIsAbsent() throws Exception {
        // Reload production classes while rejecting the optional API even though
        // Maven's test classpath includes it. Bukkit classes retain their identity.
        var classes = AudioVizPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[]{classes}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("de.maxhenkel.voicechat.")) {
                        throw new ClassNotFoundException(name);
                    }
                    if (!name.startsWith("com.audioviz.")) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            // Reproduce why registering the main plugin breaks Paper's scan.
            Class<?> plugin = loader.loadClass("com.audioviz.AudioVizPlugin");
            NoClassDefFoundError failure = assertThrows(NoClassDefFoundError.class, plugin::getDeclaredMethods);
            assertTrue(failure.getMessage().contains("de/maxhenkel/voicechat/"));

            Class<?> listener = loader.loadClass("com.audioviz.AudioVizPlugin$CoreEventListener");
            assertDoesNotThrow(listener::getMethods);
            var handlers = Arrays.stream(listener.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class))
                .collect(Collectors.toMap(method -> method.getName(), method -> method));
            assertEquals(Set.of("onWorldUnload", "onPlayerChat"), handlers.keySet());
            assertArrayEquals(new Class<?>[]{WorldUnloadEvent.class},
                handlers.get("onWorldUnload").getParameterTypes());
            assertArrayEquals(new Class<?>[]{AsyncPlayerChatEvent.class},
                handlers.get("onPlayerChat").getParameterTypes());
            EventHandler chat = handlers.get("onPlayerChat").getAnnotation(EventHandler.class);
            assertEquals(EventPriority.MONITOR, chat.priority());
            assertTrue(chat.ignoreCancelled());
        }
    }

    @Test
    void delegatesBothEventsToExistingHandlers() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        var listener = new AudioVizPlugin.CoreEventListener(plugin);
        WorldUnloadEvent unload = mock(WorldUnloadEvent.class);
        AsyncPlayerChatEvent chat = mock(AsyncPlayerChatEvent.class);

        listener.onWorldUnload(unload);
        listener.onPlayerChat(chat);

        verify(plugin).onWorldUnload(unload);
        verify(plugin).onPlayerChat(chat);
        verifyNoMoreInteractions(plugin);
    }
}
