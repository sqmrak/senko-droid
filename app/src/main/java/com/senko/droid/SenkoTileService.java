package com.senko.droid;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * the quick settings tile: one tap starts or stops the tunnel. a tile does not
 * exist before api 24, where the manifest entry is never bound. anything a tile
 * cannot ask for, the VPN consent or a server to connect to, opens the app
 */
@TargetApi(Build.VERSION_CODES.N)
public final class SenkoTileService extends TileService implements Senko.Listener {
    private Senko senko;
    // the engine was asked to start for a tap, the connect follows when it answers
    private boolean connectWhenUp;

    @Override
    public void onStartListening() {
        super.onStartListening();
        senko = Senko.get();
        senko.addListener(this);
        // counts as a screen: the engine starts, its events flow, and it is not ended as idle
        senko.screenStarted();
        senko.loadCatalog(null);
        refresh();
    }

    @Override
    public void onStopListening() {
        if (senko != null) {
            senko.removeListener(this);
            senko.screenStopped();
        }
        connectWhenUp = false;
        super.onStopListening();
    }

    @Override
    public void onClick() {
        if (senko == null || senko.disconnecting()) return;
        if (senko.isActive()) {
            senko.disconnect(null);
            return;
        }
        if (VpnService.prepare(this) != null || (senko.engineUp() && !senko.canConnect())) {
            openApp();
            return;
        }
        if (!senko.engineUp()) {
            connectWhenUp = true;
            senko.startEngine();
            refresh();
            return;
        }
        senko.connect(null);
    }

    @Override
    public void onChange(int what) {
        if (connectWhenUp && senko.engineUp()) {
            connectWhenUp = false;
            if (senko.canConnect()) senko.connect(null);
            else openApp();
        }
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null || senko == null) return;
        String state = senko.state();
        boolean on = senko.isActive() && !senko.disconnecting();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            String text;
            if (senko.disconnecting()) text = Tr.t("Disconnecting");
            else if ("connecting".equals(state) || connectWhenUp) text = Tr.t("Connecting");
            else if ("connected".equals(state)) text = Tr.t("Connected");
            else text = Tr.t("Disconnected");
            tile.setSubtitle(text);
        }
        tile.updateTile();
    }

    private void openApp() {
        Intent home = new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, home,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(home);
        }
    }
}
