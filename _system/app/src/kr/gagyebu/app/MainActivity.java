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
    static final String VERSION = "1.1";
    private static final int REQ_SAVE = 1, REQ_OPEN = 2;
    private WebView web;
    private String pendingCsv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

    @Override
    protected void onResume() {
        super.onResume();
        // 다른 앱에서 결제하고 돌아오면 새로 자동 기록된 내역이 바로 보이게 한다
        web.evaluateJavascript("window.onNativeResume&&onNativeResume()", null);
        syncNow();
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

    private void toJs(String fn, JSONObject o) {
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
