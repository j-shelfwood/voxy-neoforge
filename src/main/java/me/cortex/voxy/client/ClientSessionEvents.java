package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
    public static boolean inSession = false;

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        VoxyCommon.shutdownInstance();
    }
}
