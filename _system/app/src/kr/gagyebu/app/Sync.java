package kr.gagyebu.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 구글 스프레드시트 공유. 두 폰이 같은 시트(에 붙인 Apps Script 웹 앱 주소)에 내역을 올리고 받아 한 장부로 맞춘다.
 *   올리기: POST {key, rows:[…]}  → 시트가 고유번호(uid)로 찾아 더 나중에 고친 쪽을 남긴다
 *   받기:   GET ?key=…&since=<지난번까지 받은 번호(rev)>  → 그 뒤로 바뀐 줄만
 * 주소·암호가 설정에 없으면 아무것도 하지 않는다. 실패해도 내역은 폰에 남아 있고 다음에 다시 올린다.
 */
public final class Sync {
    private Sync() {}

    private static boolean running, again;

    /** 뒤에서 한 번 맞춘다. 이미 도는 중이면 끝난 뒤 한 번 더 돈다. done은 끝났을 때 부를 것(없어도 됨) */
    public static void kick(Context ctx, Runnable done) {
        final Context app = ctx.getApplicationContext();
        synchronized (Sync.class) {
            if (running) {
                again = true;
                return;
            }
            running = true;
        }
        new Thread(() -> {
            try {
                while (true) {
                    run(app);
                    synchronized (Sync.class) {
                        if (!again) break;
                        again = false;
                    }
                }
            } finally {
                synchronized (Sync.class) {
                    running = false;
                    again = false;
                }
                if (done != null) done.run();
            }
        }).start();
    }

    /** 한 번 맞추고 결과 문구를 설정(sync_last)에 남긴다 */
    static void run(Context ctx) {
        Db db = Db.get(ctx);
        String url = db.setting("sync_url", ""), key = db.setting("sync_key", "");
        if (url.isEmpty()) return;
        String when = new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(new Date());
        try {
            int up = 0, down = 0;
            // 1) 올리기 (300건씩)
            for (int round = 0; round < 20; round++) {
                JSONArray rows = db.dirtyRows();
                if (rows.length() == 0) break;
                JSONObject body = new JSONObject().put("key", key).put("rows", rows);
                JSONObject res = new JSONObject(http(url, body.toString()));
                if (res.has("error")) throw new IllegalStateException(res.getString("error"));
                db.markClean(rows);
                up += rows.length();
                if (rows.length() < 300) break;
            }
            // 2) 받기
            long since = Long.parseLong(db.setting("sync_rev", "0"));
            JSONObject res = new JSONObject(http(url + "?key=" + URLEncoder.encode(key, "UTF-8") + "&since=" + since, null));
            if (res.has("error")) throw new IllegalStateException(res.getString("error"));
            down = db.applyRemote(res.getJSONArray("rows"));
            db.put("sync_rev", String.valueOf(res.optLong("rev", since)));
            db.put("sync_last", when + " 완료 (올림 " + up + "건 · 받음 " + down + "건)");
        } catch (Exception e) {
            String msg = e instanceof IllegalStateException ? e.getMessage()
                    : (e instanceof java.io.IOException ? "연결 실패 (인터넷·주소 확인)" : "응답을 읽을 수 없음 (주소·배포 설정 확인)");
            db.put("sync_last", when + " 실패: " + msg);
        }
    }

    /** body가 있으면 POST, 없으면 GET. Apps Script는 302로 응답 주소를 알려 주므로 따라가서 읽는다 */
    private static String http(String url, String body) throws java.io.IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new java.io.IOException("redirect");
                url = loc;
                body = null;            // 스크립트는 이미 실행됐고, 결과만 GET으로 받는다
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("redirect loop");
    }
}
