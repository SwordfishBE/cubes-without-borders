package dev.kirant.cwb;

import com.mojang.blaze3d.platform.*;
import dev.kirant.cwb.util.*;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;

import java.util.*;
import java.util.stream.*;

public final class FullscreenTypes {
    private static final FullscreenType DEFAULT = new DefaultFullscreen();

    private static final Map<String, FullscreenType> REGISTRY = Stream.of(
        new LinuxBorderlessFullscreen(),
        new MacOSBorderlessFullscreen(),
        new WindowedFullscreen(),
        new WindowsWindowedFullscreen(),
        new HybridFullscreen(),
        FullscreenTypes.DEFAULT
    ).collect(Collectors.toMap(FullscreenType::getId, x -> x, (x, _) -> x, LinkedHashMap::new));

    public static FullscreenType validate(FullscreenType fullscreenType) {
        return FullscreenTypes.validate(fullscreenType, FullscreenTypes.DEFAULT);
    }

    public static FullscreenType validate(FullscreenType fullscreenType, FullscreenType defaultFullscreenType) {
        if (fullscreenType == null || !fullscreenType.isSupported()) {
            return FullscreenTypes.validate(defaultFullscreenType, FullscreenTypes.DEFAULT);
        }
        return fullscreenType;
    }

    public static Optional<FullscreenType> get(String id) {
        String normalizedId = id.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(FullscreenTypes.REGISTRY.get(normalizedId));
    }

    public static Stream<FullscreenType> stream() {
        return REGISTRY.values().stream().filter(FullscreenType::isSupported);
    }

    public static FullscreenType exclusive() {
        return FullscreenTypes.DEFAULT;
    }

    public static FullscreenType borderless() {
        return FullscreenTypes.stream().findFirst().get();
    }

    public static boolean isWindowed(FullscreenType type) {
        return type instanceof WindowedFullscreen || type instanceof MacOSBorderlessFullscreen;
    }

    private static boolean enableWindowed(Window window, Monitor monitor, int heightPadding) {
        long handle = window.handle();
        if (!SDLVideo.SDL_SetWindowFullscreen(handle, false) || !SDLVideo.SDL_SetWindowBordered(handle, false)) {
            return false;
        }

        window.x = monitor.x();
        window.y = monitor.y();
        window.width = monitor.currentMode().getWidth();
        window.height = monitor.currentMode().getHeight() + heightPadding;
        return SDLVideo.SDL_SetWindowSize(handle, window.width, window.height)
            && SDLVideo.SDL_SetWindowPosition(handle, window.x, window.y);
    }

    // This fullscreen type lets Minecraft handle exclusive fullscreen.
    private static class DefaultFullscreen implements FullscreenType {
        @Override
        public String getId() {
            return "minecraft:default";
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            return true;
        }

        @Override
        public void disable(Window window) { }
    }

    private static class WindowedFullscreen implements FullscreenType {
        @Override
        public String getId() {
            return "minecraft:windowed";
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            return FullscreenTypes.enableWindowed(window, monitor, 0);
        }

        @Override
        public void disable(Window window) {
            SDLVideo.SDL_SetWindowBordered(window.handle(), true);
        }
    }

    private static class HybridFullscreen implements FullscreenType {
        @Override
        public String getId() {
            return "minecraft:hybrid";
        }

        @Override
        public boolean isSupported() {
            return true;
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "1");
            return SDLVideo.SDL_SetWindowFullscreenMode(window.handle(), null)
                && SDLVideo.SDL_SetWindowFullscreen(window.handle(), true);
        }

        @Override
        public void disable(Window window) { }
    }

    private static class LinuxBorderlessFullscreen extends HybridFullscreen {
        @Override
        public String getId() {
            return "linux:borderless";
        }

        @Override
        public boolean isSupported() {
            return OS.isUnix() && !OS.isMacOS();
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0");
            return SDLVideo.SDL_SetWindowFullscreenMode(window.handle(), null)
                && SDLVideo.SDL_SetWindowFullscreen(window.handle(), true);
        }
    }

    private static class MacOSBorderlessFullscreen implements FullscreenType {
        @Override
        public String getId() {
            return "macos:borderless";
        }

        @Override
        public boolean isSupported() {
            return OS.isMacOS();
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            if (!FullscreenTypes.enableWindowed(window, monitor, 0)) {
                return false;
            }
            MinecraftWindow.MacOS.setHasShadow(window, false);
            MinecraftWindow.MacOS.hideGlobalUI();
            MinecraftWindow.MacOS.setResizable(window, false);
            return true;
        }

        @Override
        public void disable(Window window) {
            MinecraftWindow.MacOS.registerWindowWillReturnFieldEditorStub(window);
            MinecraftWindow.MacOS.showGlobalUI();
            MinecraftWindow.MacOS.setHasShadow(window, true);
            SDLVideo.SDL_SetWindowBordered(window.handle(), true);
            SDLVideo.SDL_SetWindowResizable(window.handle(), true);
            SDLVideo.SDL_RaiseWindow(window.handle());
        }
    }

    // Extending a fullscreen-sized window by one pixel avoids Windows DirectFlip blinking.
    private static class WindowsWindowedFullscreen extends WindowedFullscreen {
        @Override
        public String getId() {
            return "windows:windowed";
        }

        @Override
        public boolean isSupported() {
            return OS.isWindows();
        }

        @Override
        public boolean enable(Window window, Monitor monitor, VideoMode videoMode) {
            if (!FullscreenTypes.enableWindowed(window, monitor, 1)) {
                return false;
            }
            MinecraftWindow.Windows.pleaseStopDiscardingFuckingFramesThankYou(window);
            return true;
        }

        @Override
        public void disable(Window window) {
            MinecraftWindow.Windows.restoreStyle(window);
            super.disable(window);
        }
    }

    private FullscreenTypes() { }
}
