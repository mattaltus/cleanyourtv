package com.gathin.tvhome;

import android.media.tv.TvInputService;

/**
 * Local (living room): not a real TV input. TCL's boot manager (TclAppBoot) lets a package that declares a
 * TvInputService start its services at boot, so HomeKeyService binds after a cold boot without the
 * APP_AUTO_START app op. The manifest entry has no android.media.tv.input metadata, so the TV input framework
 * skips it and it never shows as an input. Idea from Haven (github.com/joydeepsjce/haven-tv, MIT).
 */
public class BootPassService extends TvInputService {
    @Override
    public Session onCreateSession(String inputId) {
        return null;
    }
}
