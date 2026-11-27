package io.github.zensu357.camswap;

import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Compatibility shim: upstream release tarball references this class from
 * Camera1Handler / ConfigWatcher / HookMain / MediaPlayerManager / MicrophoneHandler
 * but the source file is absent in the published tree.
 *
 * Semantics (from cs_config.json / ConfigReceiver):
 *   use_original_camera=true  -> floating control switched to "Real camera"
 *   use_original_camera=false -> normal virtual camera / stream replacement
 */
public final class SwitchablePreview {
    private SwitchablePreview() {}

    public static boolean useOriginalCamera() {
        try {
            return VideoManager.getConfig().getBoolean(
                ConfigManager.KEY_USE_ORIGINAL_CAMERA, false);
        } catch (Throwable t) {
            return false;
        }
    }
}
