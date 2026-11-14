package com.santalucia.game;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;

/** Libera o microfone para o chat de voz do cooperativo (pede a permissão do Android na primeira vez). */
public class ChromeClient extends WebChromeClient {
    static final int REQ_MIC = 7;
    private final Activity act;
    private PermissionRequest pending;

    public ChromeClient(Activity act) { this.act = act; }

    @Override
    public void onPermissionRequest(PermissionRequest req) {
        boolean audio = false;
        for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
        if (!audio) { req.deny(); return; }
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pending = req;
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    void onResult(boolean ok) {
        if (pending == null) return;
        if (ok) pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); else pending.deny();
        pending = null;
    }
}
