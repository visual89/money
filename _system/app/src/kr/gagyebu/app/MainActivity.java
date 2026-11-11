package kr.gagyebu.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 가계부 화면. 화면 자체는 assets/index.html(WebView)이고, 자료는 Bridge를 거쳐 폰 안 SQLite에 저장한다. */
public class MainActivity extends Activity {
    static final String VERSION = "1.8";
    private static final int REQ_SAVE = 1, REQ_OPEN = 2;
    private WebView web;
    private String pendingCsv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyDefaults();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    /**
     * 설치 파일에 들어 있는 기본값(assets/defaults.json — 빌드할 때 넣는다)을 처음 한 번 설정에 넣는다.
     * 지금은 구글 시트 웹 앱 주소(sync_url)뿐. 주소 칸이 비어 있을 때만 넣고, 사용자가 이미 넣은 주소는 건드리지 않는다.
     * 암호는 넣지 않는다(설치 파일이 공개돼 있어서) — 앱 설정에서 직접 입력한다.
     */
    private void applyDefaults() {
        try {
            Db db = Db.get(this);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream is = getAssets().open("defaults.json")) {
                byte[] tmp = new byte[4096];
                int n;
                while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            }
            String url = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8)).optString("sync_url");
            // 같은 기본 주소는 한 번만 넣는다(default_url에 적어 둠). 그 뒤 사용자가 지우거나 바꾼 것은 그대로 둔다
            // 주소 칸이 비었거나, 지난번 기본 주소가 그대로 들어 있을 때(사용자가 손대지 않음)는 새 기본 주소로 바꾼다 (v1.5)
            String prev = db.setting("default_url", "");
            if (url.isEmpty() || url.equals(prev)) return;
            String cur = db.setting("sync_url", "");
            if (cur.isEmpty() || cur.equals(prev)) db.setSetting("sync_url", url);
            db.put("default_url", url);
        } catch (Exception ignored) {
            // 기본값 파일이 없거나 잘못됐으면 그냥 빈 설정으로 시작한다
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 다른 앱에서 결제하고 돌아오면 새로 자동 기록된 내역이 바로 보이게 한다
        web.evaluateJavascript("window.onNativeResume&&onNativeResume()", null);
        syncNow();
        Updater.check(this, false);
    }

    /** 구글 시트와 맞추고, 끝나면 화면을 새로 그리게 한다 (주소를 안 넣었으면 아무 일도 없다) */
    void syncNow() {
        Sync.kick(this, () -> runOnUiThread(() -> web.evaluateJavascript("window.onNativeSync&&onNativeSync()", null)));
    }

    @Override
    public void onBackPressed() {
        // 열려 있는 입력 창이 있으면 그것만 닫고, 없으면 앱을 나간다
        web.evaluateJavascript("window.onNativeBack?onNativeBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    /** 설치기가 돌려주는 업데이트 결과 (Updater.install이 건 PendingIntent) */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i == null || !Updater.ACTION.equals(i.getAction())) return;
        int status = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);     // '업데이트하시겠습니까?' 확인 창
            if (confirm != null) startActivity(confirm);
            Updater.busy = false;
            return;
        }
        Updater.busy = false;
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) return;       // 보통 여기 오기 전에 앱이 새 판으로 바뀐다
        try {
            String why = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
            toJs("onNativeResult", new JSONObject().put("quiet", true).put("error",
                    status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ? "업데이트를 취소했습니다"
                            : "업데이트 실패" + (why == null ? "" : ": " + why)));
        } catch (Exception ignored) {
        }
    }

    void toJs(String fn, JSONObject o) {
        runOnUiThread(() -> web.evaluateJavascript("window." + fn + "&&" + fn + "(" + o + ")", null));
    }

    void startExport(String csv, String ym) {
        pendingCsv = csv;
        String name = "가계부_" + (ym == null || ym.equals("all") ? "전체" : ym) + "_"
                + new SimpleDateFormat("yyMMdd", Locale.KOREA).format(new Date()) + ".csv";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, name);
        runOnUiThread(() -> startActivityForResult(i, REQ_SAVE));
    }

    void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        runOnUiThread(() -> startActivityForResult(i, REQ_OPEN));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        JSONObject out = new JSONObject();
        try {
            if (req == REQ_SAVE) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                }
                pendingCsv = null;
                out.put("message", "내보내기 완료");
            } else if (req == REQ_OPEN) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = is.read(tmp)) > 0) {
                        buf.write(tmp, 0, n);
                        if (buf.size() > 10 * 1024 * 1024) throw new IllegalArgumentException("파일이 너무 큽니다 (10MB까지)");
                    }
                }
                JSONObject r = Db.get(this).importCsv(decode(buf.toByteArray()));
                out.put("message", "가져오기: 추가 " + r.getInt("added") + "건 · 중복 " + r.getInt("dup") + "건"
                        + (r.getJSONArray("errors").length() > 0 ? " · 오류 " + r.getJSONArray("errors").length() + "건" : ""));
                out.put("errors", r.getJSONArray("errors"));
            }
        } catch (Exception e) {
            try {
                out.put("error", e instanceof IllegalArgumentException ? e.getMessage() : String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        toJs("onNativeResult", out);
    }

    /** UTF-8로 읽어 보고 안 되면 엑셀이 저장한 한글 CSV(EUC-KR)로 읽는다 */
    private static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("EUC-KR"));
        }
    }
}
