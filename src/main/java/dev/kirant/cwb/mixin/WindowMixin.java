package dev.kirant.cwb.mixin;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import dev.kirant.cwb.*;
import dev.kirant.cwb.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = Window.class, priority = 0)
abstract class WindowMixin implements FullscreenManager {
    private static @Shadow @Final Logger LOGGER;

    private @Shadow @Final MonitorManager monitorManager;

    private @Shadow @Final WindowEventHandler eventHandler;

    private @Shadow Optional<VideoMode> preferredFullscreenVideoMode;

    private @Shadow boolean fullscreenRequested;

    private @Shadow boolean fullscreen;

    private @Shadow boolean exclusiveFullscreen;

    private @Shadow boolean borderlessFullscreen;

    private boolean borderless;

    private FullscreenType currentFullscreenType;

    @Shadow
    private void setMode() { }

    @Override
    public FullscreenMode getFullscreenMode() {
        return this.fullscreenRequested ? this.borderless ? FullscreenMode.BORDERLESS : FullscreenMode.ON : FullscreenMode.OFF;
    }

    @Override
    public void setFullscreenMode(FullscreenMode fullscreenMode) {
        FullscreenMode currentFullscreenMode = this.getFullscreenMode();
        this.fullscreenRequested = fullscreenMode != FullscreenMode.OFF;
        this.borderless = this.fullscreenRequested ? fullscreenMode == FullscreenMode.BORDERLESS : this.borderless;
        this.syncCurrentFullscreenState();

        if (currentFullscreenMode != fullscreenMode) {
            this.setMode();
            this.eventHandler.framebufferSizeChanged();
        }
    }

    @Inject(method = "createWindow", at = @At("HEAD"))
    private void concealCommandLine(CallbackInfoReturnable<Long> cir) {
        if (OS.isWindows()) {
            MinecraftWindow.Windows.concealCommandLine();
        }
    }

    @Inject(method = "createWindow", at = @At("RETURN"))
    private void restoreCommandLine(CallbackInfoReturnable<Long> cir) {
        if (OS.isWindows()) {
            MinecraftWindow.Windows.restoreCommandLine();
        }
    }

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long useScaledFramebuffer(long flags) {
        return CWB.CONFIG.getUseScaledFramebuffer()
            ? flags
            : flags & ~SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
    }

    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setMode()V"))
    private void init(CallbackInfo ci) {
        CWBConfig config = CWB.CONFIG;
        this.fullscreenRequested = !config.getUseDelayedFullscreen() && config.getFullscreenMode() != FullscreenMode.OFF;
        this.borderless = config.getPreferredFullscreenMode() == FullscreenMode.BORDERLESS
            || config.getFullscreenMode() == FullscreenMode.BORDERLESS;
        this.syncCurrentFullscreenState();
    }

    @Inject(method = "applyFullscreen", at = @At("HEAD"), cancellable = true)
    private void enableFullscreen(CallbackInfoReturnable<Boolean> cir) {
        Window window = (Window)(Object)this;
        CWBConfig config = CWB.CONFIG;
        FullscreenType requested = this.borderless ? config.getBorderlessFullscreenType() : config.getFullscreenType();
        FullscreenType fallback = this.borderless ? FullscreenTypes.borderless() : FullscreenTypes.exclusive();
        FullscreenType next = FullscreenTypes.validate(requested, fallback);

        if (this.currentFullscreenType != null && this.currentFullscreenType != next) {
            this.currentFullscreenType.disable(window);
            this.borderlessFullscreen = false;
        }

        this.currentFullscreenType = next;
        if (next == FullscreenTypes.exclusive()) {
            this.exclusiveFullscreen = true;
            return;
        }

        Monitor monitor = this.monitorManager.findBestMonitor(window);
        if (monitor == null) {
            this.currentFullscreenType = null;
            return;
        }

        boolean enabled = next.enable(window, monitor, monitor.getPreferredVideoMode(this.preferredFullscreenVideoMode));
        if (!enabled) {
            LOGGER.error("Failed to enable {} fullscreen mode.", next.getId());
            this.currentFullscreenType = null;
        }
        this.borderlessFullscreen = enabled && FullscreenTypes.isWindowed(next);
        cir.setReturnValue(enabled);
    }

    @Inject(method = "applyWindowed", at = @At("HEAD"))
    private void disableFullscreen(CallbackInfoReturnable<Boolean> cir) {
        if (this.currentFullscreenType != null) {
            this.currentFullscreenType.disable((Window)(Object)this);
            this.currentFullscreenType = null;
            this.borderlessFullscreen = false;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void save(CallbackInfo ci) {
        CWBConfig config = CWB.CONFIG;
        config.setFullscreenMode(this.getFullscreenMode());
        config.setPreferredFullscreenMode(this.borderless ? FullscreenMode.BORDERLESS : FullscreenMode.ON);
        config.save();
    }

    private void syncCurrentFullscreenState() {
        Minecraft.getInstance().options.fullscreen().set(this.fullscreenRequested);
    }
}
