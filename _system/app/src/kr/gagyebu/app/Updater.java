package kr.gagyebu.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 자동 업데이트 (v1.3). 배포 저장소(GitHub)의 version.json을 보고 새 판이 있으면 화면에 알리고,
 * 사용자가 [업데이트]를 누르면 gagyebu.apk를 받아 안드로이드 설치기(PackageInstaller)에 넘긴다.
 *  - 안드로이드는 같은 서명 열쇠로 만든 설치 파일만 덮어 설치해 준다 → 남이 만든 파일로 바뀔 수 없다.
 *  - 받은 파일의 SHA-256이 version.json과 같아야 설치한다 (받다가 깨진 파일·아직 덜 올라온 파일 방지).
 *  - 처음 한 번은 '이 출처 허용'과 설치 확인 창이 뜬다. 그 뒤(안드로이드 12 이상)는 확인 창 없이 설치될 수 있다.
 */
public final class Updater {
    private Updater() {}

    static final String BASE = "https://raw.githubusercontent.com/visual89/money/main/";
    static final String ACTION = "kr.gagyebu.app.INSTALL_RESULT";
    private static final long CHECK_EVERY = 3 * 3600 * 1000L;       // 자동 확인은 3시간에 한 번
    private static volatile JSONObject latest;                       // 새 판 정보 (없으면 null)
    static volatile boolean busy;

    static int currentCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 새 판이 있는지 본다. 결과는 화면의 onNativeUpdate({version, notes} | {latest:true} | {error})로 간다 */
    static void check(MainActivity act, boolean force) {
        final Db db = Db.get(act);
        final long now = System.currentTimeMillis();
        if (!force && now - Long.parseLong(db.setting("upd_checked", "0")) < CHECK_EVERY) {
            JSONObject v = latest;
            if (v != null) act.toJs("onNativeUpdate", info(v, false));
            return;
        }
        new Thread(() -> {
            JSONObject out = new JSONObject();
            try {
                JSONObject v = new JSONObject(new String(get(BASE + "version.json"), StandardCharsets.UTF_8));
                db.put("upd_checked", String.valueOf(now));
                if (v.optInt("code") > currentCode(act)) {
                    latest = v;
                    out = info(v, force);
                } else {
                    latest = null;
                    out.put("latest", true).put("force", force);
                }
            } catch (Exception e) {
                try {
                    out.put("error", "업데이트 확인 실패 (인터넷 연결 확인)").put("force", force);
                } catch (Exception ignored) {
                }
            }
            act.toJs("onNativeUpdate", out);
        }).start();
    }

    private static JSONObject info(JSONObject v, boolean force) {
        JSONObject o = new JSONObject();
        try {
            o.put("version", v.optString("version")).put("notes", v.optString("notes")).put("force", force);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 새 판을 받아 설치기를 띄운다. 진행·실패 문구는 onNativeResult로 간다 */
    static void install(MainActivity act) {
        final JSONObject v = latest;
        if (v == null) {
            msg(act, "error", "새 버전 정보가 없습니다. [업데이트 확인]을 먼저 눌러 주세요");
            return;
        }
        if (busy) return;
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            // 처음 한 번: 이 앱이 설치 파일을 설치해도 된다고 허용해야 한다
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            msg(act, "message", "'이 출처 허용'을 켜고 돌아와 [업데이트]를 다시 눌러 주세요");
            return;
        }
        busy = true;
        msg(act, "message", "새 버전을 받는 중…");
        new Thread(() -> {
            try {
                byte[] apk = get(BASE + "gagyebu.apk");
                String want = v.optString("sha256");
                if (!want.isEmpty() && !want.equalsIgnoreCase(sha256(apk)))
                    throw new IllegalStateException("새 파일이 아직 준비 중입니다. 몇 분 뒤 다시 눌러 주세요");
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(act.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (OutputStream os = s.openWrite("base.apk", 0, apk.length)) {
                        os.write(apk);
                        s.fsync(os);
                    }
                    Intent back = new Intent(act, MainActivity.class).setAction(ACTION);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getActivity(act, id, back, flags).getIntentSender());
                }
                // 여기부터는 안드로이드 설치기가 이어받는다 (확인 창 → 설치 → 앱이 새 판으로 바뀜). 결과는 MainActivity.onNewIntent
            } catch (Exception e) {
                busy = false;
                msg(act, "error", e instanceof IllegalStateException ? e.getMessage()
                        : (e instanceof java.io.IOException ? "내려받기 실패 (인터넷 연결 확인)" : "설치를 시작하지 못했습니다: " + e));
            }
        }).start();
    }

    private static void msg(MainActivity act, String key, String text) {
        try {
            act.toJs("onNativeResult", new JSONObject().put(key, text).put("quiet", true));
        } catch (Exception ignored) {
        }
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[16384];
                int n;
                while ((n = is.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                    if (buf.size() > 50 * 1024 * 1024) throw new java.io.IOException("too big");
                }
                return buf.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
