package kr.gagyebu.app;

import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** 화면(index.html)의 자바스크립트가 부르는 창구. 모든 함수는 JSON 문자열을 돌려주고, 실패하면 {"error": "..."} */
public class Bridge {
    private final MainActivity act;
    private final Db db;

    Bridge(MainActivity act) {
        this.act = act;
        this.db = Db.get(act);
    }

    private interface Job {
        Object run() throws Exception;
    }

    private String wrap(Job j) {
        try {
            Object o = j.run();
            return o == null ? "{\"ok\":true}" : o.toString();
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err(String.valueOf(e));
        }
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("error", msg == null ? "오류" : msg).toString();
        } catch (Exception e) {
            return "{\"error\":\"오류\"}";
        }
    }

    @JavascriptInterface
    public String meta() {
        return wrap(() -> db.meta().put("noti", notiEnabled()).put("version", MainActivity.VERSION));
    }

    @JavascriptInterface
    public String month(String ym) {
        return wrap(() -> db.month(ym));
    }

    @JavascriptInterface
    public String year(String y) {
        return wrap(() -> db.year(y));
    }

    @JavascriptInterface
    public String search(String q) {
        return wrap(() -> db.search(q));
    }

    @JavascriptInterface
    public String save(String json) {
        return wrap(() -> new JSONObject().put("ok", true).put("id", db.save(new JSONObject(json))));
    }

    @JavascriptInterface
    public String remove(String id) {
        return wrap(() -> {
            db.remove(Long.parseLong(id));
            return null;
        });
    }

    @JavascriptInterface
    public String category(String kind, String name, String action) {
        return wrap(() -> {
            db.category(kind, name, action);
            return null;
        });
    }

    @JavascriptInterface
    public String setSetting(String key, String value) {
        return wrap(() -> {
            db.setSetting(key, value == null ? "" : value);
            return null;
        });
    }

    /** 구글 시트와 지금 맞춘다. 끝나면 화면의 onNativeSync()가 불린다 */
    @JavascriptInterface
    public String sync() {
        return wrap(() -> {
            act.syncNow();
            return null;
        });
    }

    @JavascriptInterface
    public String log() {
        return wrap(db::log);
    }

    /** 설정 화면의 '알림 문구 시험': 붙여 넣은 문구가 어떻게 읽히는지 보여 준다 (저장하지 않음) */
    @JavascriptInterface
    public String testParse(String text) {
        return wrap(() -> {
            Parser.Result r = Parser.parse("", "", text == null ? "" : text);
            JSONObject o = new JSONObject();
            o.put("ok", r.ok);
            o.put("kind", r.kind);
            o.put("amount", r.amount);
            o.put("merchant", r.merchant);
            o.put("method", r.method);
            o.put("category", r.ok ? Parser.guessCategory(r.merchant) : "");
            o.put("reason", r.reason);
            return o;
        });
    }

    private boolean notiEnabled() {
        String on = Settings.Secure.getString(act.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(new ComponentName(act, NotiListener.class).flattenToString());
    }

    @JavascriptInterface
    public String openNotiSettings() {
        return wrap(() -> {
            act.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return null;
        });
    }

    /** 앱 정보 화면 — 안드로이드 13 이상에서 '제한된 설정 허용'을 누르는 곳 */
    @JavascriptInterface
    public String openAppInfo() {
        return wrap(() -> {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + act.getPackageName())));
            return null;
        });
    }

    @JavascriptInterface
    public String exportCsv(String ym) {
        return wrap(() -> {
            act.startExport(db.csv(ym), ym);
            return null;
        });
    }

    @JavascriptInterface
    public String importCsv() {
        return wrap(() -> {
            act.startImport();
            return null;
        });
    }
}
