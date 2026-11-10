package com.senko.droid;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** kept apart so the screens never name VpnService on android before 4.0, which has no such class */
@android.annotation.TargetApi(14)
final class VpnConsent {
    private VpnConsent() {}

    /** the system's consent screen, null once the user has already agreed */
    static Intent prepare(Context context) {
        return VpnService.prepare(context);
    }
}
