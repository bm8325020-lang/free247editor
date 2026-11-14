package com.santalucia.game;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Serve assets/www numa origem https local: módulos ES funcionam offline, sem servidor. */
public class AssetClient extends WebViewClient {
    private final Context ctx;

    public AssetClient(Context ctx) { this.ctx = ctx; }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        Uri u = req.getUrl();
        if (!MainActivity.HOST.equals(u.getHost())) return null; // fontes online, se houver internet
        String path = u.getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        try {
            InputStream in = ctx.getAssets().open("www" + path);
            WebResourceResponse r = new WebResourceResponse(mime(path), "utf-8", in);
            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
        }
    }

    static String mime(String p) {
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
