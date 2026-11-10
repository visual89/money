package kr.gagyebu.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 가계부 저장소 (폰 안 SQLite). 화면(Bridge)·알림 읽기(NotiListener)·구글 시트 공유(Sync)가 같이 쓴다.
 * 공유를 위해 내역마다 고유번호(uid)가 있고, 고친 내역은 dirty=1, 지운 내역은 deleted=1로 남겨 상대 폰에도 전해지게 한다.
 */
public class Db extends SQLiteOpenHelper {
    private static Db inst;
    private static final long DUP_WINDOW = 10 * 60 * 1000L;     // 같은 금액 알림이 10분 안에 또 오면 같은 결제로 본다
    private static final int LOG_KEEP = 150;
    static final String[] METHODS = {"카드", "현금", "계좌이체", "간편결제"};
    static final String[] CSV_HEAD = {"날짜", "시간", "구분", "분류", "금액", "결제수단", "내용", "사용자"};
    static final String[] SYNC_FIELDS = {"uid", "date", "time", "kind", "category", "method", "memo", "who"};
    private static final String[] CAT_OUT = {"식비", "카페·간식", "교통", "주거·관리비", "통신", "생활용품", "의료·건강", "교육",
            "문화·여가", "의류·미용", "경조사", "보험", "저축·투자", "기타", "미분류"};
    private static final String[] CAT_IN = {"월급", "상여", "용돈", "이자·배당", "결제취소", "기타수입"};

    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private Db(Context c) {
        super(c, "ledger.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, time TEXT NOT NULL DEFAULT '',"
                + " kind TEXT NOT NULL, category TEXT NOT NULL DEFAULT '', amount INTEGER NOT NULL,"
                + " method TEXT NOT NULL DEFAULT '', memo TEXT NOT NULL DEFAULT '', who TEXT NOT NULL DEFAULT '',"
                + " source TEXT NOT NULL DEFAULT '', raw TEXT NOT NULL DEFAULT '', auto INTEGER NOT NULL DEFAULT 0,"
                + " created INTEGER NOT NULL, updated INTEGER NOT NULL,"
                + " uid TEXT NOT NULL DEFAULT '', dirty INTEGER NOT NULL DEFAULT 1, deleted INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE INDEX idx_entries_date ON entries(date)");
        d.execSQL("CREATE INDEX idx_entries_uid ON entries(uid)");
        d.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL,"
                + " sort INTEGER NOT NULL DEFAULT 0, UNIQUE(kind, name))");
        d.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        d.execSQL("CREATE TABLE rules (merchant TEXT PRIMARY KEY, category TEXT NOT NULL)");
        d.execSQL("CREATE TABLE notilog (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, label TEXT, title TEXT,"
                + " text TEXT, result TEXT, hash TEXT UNIQUE)");
        for (int i = 0; i < CAT_OUT.length; i++) d.execSQL("INSERT INTO categories(kind,name,sort) VALUES ('out',?,?)", new Object[]{CAT_OUT[i], i});
        for (int i = 0; i < CAT_IN.length; i++) d.execSQL("INSERT INTO categories(kind,name,sort) VALUES ('in',?,?)", new Object[]{CAT_IN[i], i});
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int from, int to) {
        if (from < 2) {     // v1.0 → v1.1: 구글 시트 공유용 열 추가
            d.execSQL("ALTER TABLE entries ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
            d.execSQL("ALTER TABLE entries ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1");
            d.execSQL("ALTER TABLE entries ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
            d.execSQL("UPDATE entries SET uid=lower(hex(randomblob(16))), dirty=1");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_uid ON entries(uid)");
        }
    }

    // ───────────── 도우미 ─────────────
    private static String fmt(String p, long ts) {
        return new SimpleDateFormat(p, Locale.KOREA).format(new Date(ts));
    }

    private static String newUid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static JSONObject entry(Cursor c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", c.getLong(c.getColumnIndexOrThrow("id")));
        for (String k : new String[]{"date", "time", "kind", "category", "method", "memo", "who", "source", "raw"})
            o.put(k, c.getString(c.getColumnIndexOrThrow(k)));
        o.put("amount", c.getLong(c.getColumnIndexOrThrow("amount")));
        o.put("auto", c.getInt(c.getColumnIndexOrThrow("auto")));
        return o;
    }

    private JSONArray entries(String where, String[] args, String limit) throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().query("entries", null, "deleted=0 AND (" + where + ")", args, null, null,
                "date DESC, time DESC, id DESC", limit)) {
            while (c.moveToNext()) out.put(entry(c));
        }
        return out;
    }

    public String setting(String key, String def) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : def;
        }
    }

    /** 앱 안에서만 쓰는 값 저장 (검사 없음) */
    void put(String key, String value) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES (?,?)", new Object[]{key, value});
    }

    /** 화면에서 바꾸는 설정 */
    public synchronized void setSetting(String key, String value) {
        value = value == null ? "" : value.trim();
        if (key.equals("budget")) {
            value = value.replaceAll("[,\\s원]", "");
            if (value.isEmpty()) value = "0";
            if (!value.matches("\\d{1,12}")) throw new IllegalArgumentException("예산은 숫자로 입력해 주세요");
        } else if (key.equals("who")) {
            if (value.length() > 10) value = value.substring(0, 10);
        } else if (key.equals("sync_url")) {
            if (!value.isEmpty() && !value.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_\\-]+/exec"))
                throw new IllegalArgumentException("주소는 https://script.google.com/macros/s/…/exec 꼴이어야 합니다");
            if (!value.equals(setting("sync_url", ""))) {
                // 시트가 바뀌면 처음부터 다시 맞춘다: 내 내역 전부 올리고, 시트 내역 전부 받는다
                put("sync_rev", "0");
                getWritableDatabase().execSQL("UPDATE entries SET dirty=1");
            }
        } else if (key.equals("sync_key")) {
            if (value.length() > 60) throw new IllegalArgumentException("암호가 너무 깁니다");
        } else {
            throw new IllegalArgumentException("알 수 없는 설정");
        }
        put(key, value);
    }

    static String normDate(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})\\s*[-./]?\\s*(\\d{1,2})\\s*[-./]?\\s*(\\d{1,2})\\.?(?:\\s.*)?").matcher(s == null ? "" : s.trim());
        if (!m.matches()) throw new IllegalArgumentException("날짜를 알 수 없습니다: " + s);
        int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setLenient(false);
        cal.clear();
        cal.set(y, mo - 1, d);
        try {
            cal.getTimeInMillis();
        } catch (Exception e) {
            throw new IllegalArgumentException("없는 날짜입니다: " + s);
        }
        return String.format(Locale.US, "%04d-%02d-%02d", y, mo, d);
    }

    static long normAmount(String v) {
        String s = (v == null ? "" : v).replaceAll("[,\\s원₩]", "");
        if (s.matches("-?\\d{1,11}(\\.0+)?")) {
            long n = Math.abs((long) Double.parseDouble(s));
            if (n > 0) return n;
        }
        throw new IllegalArgumentException("금액을 알 수 없습니다: " + v);
    }

    static String normKind(String k) {
        if ("in".equals(k) || "수입".equals(k)) return "in";
        if ("out".equals(k) || "지출".equals(k)) return "out";
        throw new IllegalArgumentException("구분은 수입 또는 지출이어야 합니다");
    }

    private static String cut(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ───────────── 조회 ─────────────
    public JSONObject meta() throws JSONException {
        JSONObject o = new JSONObject();
        JSONObject cats = new JSONObject();
        cats.put("in", new JSONArray());
        cats.put("out", new JSONArray());
        try (Cursor c = getReadableDatabase().rawQuery("SELECT kind, name FROM categories ORDER BY sort, id", null)) {
            while (c.moveToNext()) cats.getJSONArray(c.getString(0)).put(c.getString(1));
        }
        o.put("categories", cats);
        o.put("methods", new JSONArray(METHODS));
        o.put("budget", Long.parseLong(setting("budget", "0")));
        o.put("who", setting("who", ""));
        o.put("sync_url", setting("sync_url", ""));
        o.put("sync_key", setting("sync_key", ""));
        o.put("sync_last", setting("sync_last", ""));
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(dirty),0) FROM entries WHERE deleted=0 OR dirty=1", null)) {
            c.moveToFirst();
            o.put("pending", c.getLong(1));
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE deleted=0", null)) {
            c.moveToFirst();
            o.put("count", c.getLong(0));
        }
        return o;
    }

    public JSONArray month(String ym) throws JSONException {
        if (!ym.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException("월 형식이 잘못되었습니다");
        return entries("date LIKE ?", new String[]{ym + "-%"}, null);
    }

    public JSONArray search(String q) throws JSONException {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return new JSONArray();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return entries("memo LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\' OR method LIKE ? ESCAPE '\\' OR who LIKE ? ESCAPE '\\'",
                new String[]{like, like, like, like}, "500");
    }

    public JSONObject year(String y) throws JSONException {
        if (!y.matches("\\d{4}")) throw new IllegalArgumentException("연도 형식이 잘못되었습니다");
        JSONArray months = new JSONArray();
        for (int m = 1; m <= 12; m++) {
            JSONObject o = new JSONObject();
            o.put("ym", String.format(Locale.US, "%s-%02d", y, m));
            o.put("in", 0);
            o.put("out", 0);
            months.put(o);
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT substr(date,6,2), kind, SUM(amount) FROM entries WHERE deleted=0 AND date LIKE ? GROUP BY 1, 2", new String[]{y + "-%"})) {
            while (c.moveToNext()) months.getJSONObject(Integer.parseInt(c.getString(0)) - 1).put(c.getString(1), c.getLong(2));
        }
        JSONArray cats = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) s FROM entries WHERE deleted=0 AND date LIKE ? AND kind='out' GROUP BY category ORDER BY s DESC",
                new String[]{y + "-%"})) {
            while (c.moveToNext()) cats.put(new JSONArray().put(c.getString(0).isEmpty() ? "미분류" : c.getString(0)).put(c.getLong(1)));
        }
        JSONObject o = new JSONObject();
        o.put("year", y);
        o.put("months", months);
        o.put("categories", cats);
        return o;
    }

    // ───────────── 쓰기 ─────────────
    public synchronized long save(JSONObject b) throws JSONException {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        String kind = normKind(b.optString("kind"));
        String memo = cut(b.optString("memo"), 100), cat = cut(b.optString("category"), 30);
        v.put("date", normDate(b.optString("date")));
        v.put("kind", kind);
        v.put("category", cat);
        v.put("amount", normAmount(b.optString("amount")));
        v.put("method", cut(b.optString("method"), 20));
        v.put("memo", memo);
        v.put("updated", now);
        v.put("dirty", 1);
        SQLiteDatabase d = getWritableDatabase();
        long id = b.optLong("id", 0);
        if (id > 0) {
            if (d.update("entries", v, "id=? AND deleted=0", new String[]{String.valueOf(id)}) == 0)
                throw new IllegalArgumentException("고칠 내역을 찾을 수 없습니다");
        } else {
            String date = v.getAsString("date");
            v.put("time", date.equals(fmt("yyyy-MM-dd", now)) ? fmt("HH:mm", now) : "");
            v.put("who", setting("who", ""));
            v.put("created", now);
            v.put("uid", newUid());
            id = d.insertOrThrow("entries", null, v);
        }
        // 사용처 → 분류를 기억해 다음 자동 기록에 쓴다
        if (kind.equals("out") && !memo.isEmpty() && !cat.isEmpty() && !cat.equals("미분류"))
            d.execSQL("INSERT OR REPLACE INTO rules(merchant, category) VALUES (?,?)", new Object[]{memo, cat});
        return id;
    }

    /** 지운 내역은 표시만 해 두었다가(상대 폰에도 지워지도록) 공유가 끝나면 그대로 숨겨 둔다 */
    public synchronized void remove(long id) {
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        v.put("dirty", 1);
        v.put("updated", System.currentTimeMillis());
        if (getWritableDatabase().update("entries", v, "id=? AND deleted=0", new String[]{String.valueOf(id)}) == 0)
            throw new IllegalArgumentException("지울 내역을 찾을 수 없습니다");
    }

    public synchronized void category(String kind, String name, String action) {
        kind = normKind(kind);
        name = cut(name, 30);
        if (name.isEmpty()) throw new IllegalArgumentException("분류 이름을 입력해 주세요");
        SQLiteDatabase d = getWritableDatabase();
        if ("add".equals(action)) {
            try (Cursor c = d.rawQuery("SELECT 1 FROM categories WHERE kind=? AND name=?", new String[]{kind, name})) {
                if (c.moveToFirst()) throw new IllegalArgumentException("이미 있는 분류입니다");
            }
            d.execSQL("INSERT INTO categories(kind,name,sort) VALUES (?,?,(SELECT COALESCE(MAX(sort),0)+1 FROM categories WHERE kind=?))",
                    new Object[]{kind, name, kind});
        } else if ("delete".equals(action)) {
            // 목록에서만 뺀다. 이미 적은 내역의 분류 이름은 그대로 둔다
            d.delete("categories", "kind=? AND name=?", new String[]{kind, name});
        } else {
            throw new IllegalArgumentException("알 수 없는 요청");
        }
    }

    private void ensureCategory(SQLiteDatabase d, String kind, String name) {
        if (name == null || name.isEmpty()) return;
        d.execSQL("INSERT OR IGNORE INTO categories(kind,name,sort) VALUES (?,?,(SELECT COALESCE(MAX(sort),0)+1 FROM categories WHERE kind=?))",
                new Object[]{kind, name, kind});
    }

    /**
     * 사용자가 내역에서 정해 둔 분류 찾기: 같은 사용처가 있으면 그 분류, 없으면 가장 비슷한 사용처의 분류 (v1.6).
     * 정해 둔 것이 없으면 빈 문자열.
     */
    public String learnedCategory(String merchant) {
        merchant = merchant == null ? "" : merchant.trim();
        if (merchant.isEmpty()) return "";
        String best = "";
        int bestScore = 0, bestLen = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT merchant, category FROM rules", null)) {
            while (c.moveToNext()) {
                String m = c.getString(0);
                if (m.equals(merchant)) return c.getString(1);
                int s = Parser.similarity(m, merchant);
                if (s > bestScore || (s == bestScore && s > 0 && m.length() > bestLen)) {
                    bestScore = s;
                    bestLen = m.length();
                    best = c.getString(1);
                }
            }
        }
        return best;
    }

    String categoryFor(String merchant) {
        String learned = learnedCategory(merchant);
        return learned.isEmpty() ? Parser.guessCategory(merchant) : learned;
    }

    // ───────────── 알림 자동 기록 ─────────────
    /** 알림 하나를 받아 결제면 가계부에 적는다. 돌려주는 값은 처리 결과(기록·중복·건너뜀 까닭). */
    public synchronized String capture(String pkg, String label, String title, String text, long ts) {
        if (!Parser.acceptSource(label, title, text)) return "대상 앱 아님";
        String hash = Integer.toHexString((pkg + "|" + title + "|" + text).hashCode());
        SQLiteDatabase d = getWritableDatabase();
        try (Cursor c = d.rawQuery("SELECT 1 FROM notilog WHERE hash=?", new String[]{hash})) {
            if (c.moveToFirst()) return "같은 알림";      // 같은 알림이 다시 올라온 것(갱신)
        }
        Parser.Result r = Parser.parse(label, title, text);
        String result;
        if (!r.ok) {
            result = "건너뜀: " + r.reason;
        } else {
            String raw = "[" + label + "] " + title + "\n" + text;
            long dupId = 0;
            String dupMemo = "";
            try (Cursor c = d.rawQuery("SELECT id, memo FROM entries WHERE deleted=0 AND auto=1 AND kind=? AND amount=? AND created>=? ORDER BY id DESC LIMIT 1",
                    new String[]{r.kind, String.valueOf(r.amount), String.valueOf(ts - DUP_WINDOW)})) {
                if (c.moveToFirst()) {
                    dupId = c.getLong(0);
                    dupMemo = c.getString(1);
                }
            }
            if (dupId > 0) {
                // 같은 결제의 두 번째 알림(예: 네이버 구매 알림 + 카드사 승인 알림). 한 건으로 두고 더 구체적인 사용처를 남긴다
                if (Parser.isGateway(dupMemo) && !Parser.isGateway(r.merchant)) {
                    d.execSQL("UPDATE entries SET memo=?, category=?, raw=raw||?, updated=?, dirty=1 WHERE id=?",
                            new Object[]{r.merchant, r.kind.equals("out") ? categoryFor(r.merchant) : "결제취소", "\n\n" + raw, ts, dupId});
                } else {
                    d.execSQL("UPDATE entries SET raw=raw||? WHERE id=?", new Object[]{"\n\n" + raw, dupId});
                }
                result = "중복(한 건으로 합침): " + r.amount + "원";
            } else {
                ContentValues v = new ContentValues();
                v.put("date", fmt("yyyy-MM-dd", ts));
                v.put("time", fmt("HH:mm", ts));
                v.put("kind", r.kind);
                v.put("category", r.kind.equals("out") ? categoryFor(r.merchant)
                        : (r.merchant.contains("취소") ? "결제취소" : "기타수입"));
                v.put("amount", r.amount);
                v.put("method", r.method);
                v.put("memo", r.merchant);
                v.put("who", setting("who", ""));
                v.put("source", label);
                v.put("raw", raw);
                v.put("auto", 1);
                v.put("created", ts);
                v.put("updated", ts);
                v.put("uid", newUid());
                v.put("dirty", 1);
                d.insert("entries", null, v);
                result = "기록: " + r.merchant + " " + r.amount + "원";
            }
        }
        ContentValues lv = new ContentValues();
        lv.put("ts", ts);
        lv.put("label", label);
        lv.put("title", title);
        lv.put("text", text);
        lv.put("result", result);
        lv.put("hash", hash);
        d.insert("notilog", null, lv);
        d.execSQL("DELETE FROM notilog WHERE id <= (SELECT MAX(id) FROM notilog) - " + LOG_KEEP);
        return result;
    }

    public JSONArray log() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts, label, title, text, result FROM notilog ORDER BY id DESC LIMIT 50", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("when", fmt("MM.dd HH:mm", c.getLong(0)));
                o.put("label", c.getString(1));
                o.put("title", c.getString(2));
                o.put("text", c.getString(3));
                o.put("result", c.getString(4));
                out.put(o);
            }
        }
        return out;
    }

    // ───────────── 구글 시트 공유 (Sync가 부른다) ─────────────
    /** 아직 시트에 올리지 않은 내역 (지운 것 포함). 한 번에 300건까지 */
    public synchronized JSONArray dirtyRows() throws JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT uid, date, time, kind, category, method, memo, who, amount, updated, deleted FROM entries WHERE dirty=1 ORDER BY id LIMIT 300", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                for (int i = 0; i < SYNC_FIELDS.length; i++) o.put(SYNC_FIELDS[i], c.getString(i));
                o.put("amount", c.getLong(8));
                o.put("updated", c.getLong(9));
                o.put("deleted", c.getInt(10));
                out.put(o);
            }
        }
        return out;
    }

    /** 올리는 데 성공한 내역의 표시를 지운다. 올리는 사이에 또 고친 내역(updated가 달라짐)은 남겨 둔다 */
    public synchronized void markClean(JSONArray rows) throws JSONException {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                d.execSQL("UPDATE entries SET dirty=0 WHERE uid=? AND updated=?", new Object[]{r.getString("uid"), r.getLong("updated")});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** 시트에서 받은 내역을 반영한다. 내 폰에서 더 나중에 고친 내역은 건드리지 않는다. 돌려주는 값 = 바뀐 건수 */
    public synchronized int applyRemote(JSONArray rows) throws JSONException {
        SQLiteDatabase d = getWritableDatabase();
        int changed = 0;
        long now = System.currentTimeMillis();
        d.beginTransaction();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                String uid = r.optString("uid");
                if (uid.isEmpty()) continue;
                String kind;
                long amount;
                String date;
                try {
                    kind = normKind(r.optString("kind"));
                    amount = normAmount(r.optString("amount"));
                    date = normDate(r.optString("date"));
                } catch (IllegalArgumentException e) {
                    continue;       // 시트에서 손으로 망가뜨린 줄은 건너뛴다
                }
                long updated = r.optLong("updated", 0);
                int deleted = r.optInt("deleted", 0) != 0 ? 1 : 0;
                long localUpdated = -1;
                int localDirty = 0;
                try (Cursor c = d.rawQuery("SELECT updated, dirty FROM entries WHERE uid=?", new String[]{uid})) {
                    if (c.moveToFirst()) {
                        localUpdated = c.getLong(0);
                        localDirty = c.getInt(1);
                    }
                }
                if (localUpdated >= 0 && (localUpdated > updated || (localDirty == 1 && localUpdated == updated))) continue;
                if (localUpdated == updated && localUpdated >= 0) continue;         // 이미 같은 내용
                if (localUpdated < 0 && deleted == 1) continue;                     // 모르는 내역의 삭제 소식
                ContentValues v = new ContentValues();
                v.put("date", date);
                v.put("time", cut(r.optString("time"), 5));
                v.put("kind", kind);
                v.put("category", cut(r.optString("category"), 30));
                v.put("amount", amount);
                v.put("method", cut(r.optString("method"), 20));
                v.put("memo", cut(r.optString("memo"), 100));
                v.put("who", cut(r.optString("who"), 10));
                v.put("updated", updated);
                v.put("deleted", deleted);
                v.put("dirty", 0);
                if (localUpdated >= 0) {
                    d.update("entries", v, "uid=?", new String[]{uid});
                } else {
                    v.put("uid", uid);
                    v.put("source", "공유");
                    v.put("created", now);
                    d.insert("entries", null, v);
                }
                if (deleted == 0) ensureCategory(d, kind, v.getAsString("category"));
                changed++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return changed;
    }

    // ───────────── CSV 내보내기·가져오기 (보관·엑셀용) ─────────────
    private static String q(String s) {
        s = s == null ? "" : s;
        return s.matches("(?s).*[\",\\n\\r].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public String csv(String ym) {
        StringBuilder sb = new StringBuilder("﻿").append(String.join(",", CSV_HEAD)).append("\r\n");
        boolean all = ym == null || ym.isEmpty() || ym.equals("all");
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT date, time, kind, category, amount, method, memo, who FROM entries WHERE deleted=0"
                        + (all ? "" : " AND date LIKE ?") + " ORDER BY date, time, id", all ? null : new String[]{ym + "-%"})) {
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append(',').append(c.getString(1)).append(',')
                        .append(c.getString(2).equals("in") ? "수입" : "지출").append(',').append(q(c.getString(3))).append(',')
                        .append(c.getLong(4)).append(',').append(q(c.getString(5))).append(',')
                        .append(q(c.getString(6))).append(',').append(q(c.getString(7))).append("\r\n");
            }
        }
        return sb.toString();
    }

    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : rows) {
            boolean any = false;
            for (String s : r) if (!s.trim().isEmpty()) any = true;
            if (any) out.add(r);
        }
        return out;
    }

    /** 내보내기와 같은 열의 CSV를 넣는다. 날짜·시간·구분·금액·내용이 같은 내역이 이미 있으면 건너뛴다. */
    public synchronized JSONObject importCsv(String text) throws JSONException {
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> rows = parseCsv(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("빈 파일입니다");
        List<String> head = new ArrayList<>();
        for (String h : rows.get(0)) head.add(h.trim());
        for (String need : new String[]{"날짜", "구분", "금액"})
            if (!head.contains(need)) throw new IllegalArgumentException("첫 줄에 '" + need + "' 열이 필요합니다");
        int added = 0, dup = 0;
        JSONArray errors = new JSONArray();
        SQLiteDatabase d = getWritableDatabase();
        long now = System.currentTimeMillis();
        d.beginTransaction();
        try {
            for (int n = 1; n < rows.size(); n++) {
                List<String> r = rows.get(n);
                String[] f = new String[CSV_HEAD.length];
                for (int i = 0; i < CSV_HEAD.length; i++) {
                    int idx = head.indexOf(CSV_HEAD[i]);
                    f[i] = idx >= 0 && idx < r.size() ? r.get(idx).trim() : "";
                }
                String date, kind;
                long amount;
                try {
                    date = normDate(f[0]);
                    kind = normKind(f[2]);
                    amount = normAmount(f[4]);
                } catch (IllegalArgumentException ex) {
                    if (errors.length() < 20) errors.put((n + 1) + "번째 줄: " + ex.getMessage());
                    continue;
                }
                String time = f[1].matches("\\d{1,2}:\\d{2}.*") ? f[1].substring(0, f[1].indexOf(':') + 3) : "";
                if (time.length() == 4) time = "0" + time;
                String memo = cut(f[6], 100);
                try (Cursor c = d.rawQuery("SELECT 1 FROM entries WHERE deleted=0 AND date=? AND time=? AND kind=? AND amount=? AND memo=?",
                        new String[]{date, time, kind, String.valueOf(amount), memo})) {
                    if (c.moveToFirst()) {
                        dup++;
                        continue;
                    }
                }
                ContentValues v = new ContentValues();
                v.put("date", date);
                v.put("time", time);
                v.put("kind", kind);
                v.put("category", cut(f[3], 30));
                v.put("amount", amount);
                v.put("method", cut(f[5], 20));
                v.put("memo", memo);
                v.put("who", cut(f[7], 10));
                v.put("source", "가져오기");
                v.put("created", now);
                v.put("updated", now);
                v.put("uid", newUid());
                v.put("dirty", 1);
                d.insert("entries", null, v);
                ensureCategory(d, kind, cut(f[3], 30));
                added++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        JSONObject o = new JSONObject();
        o.put("added", added);
        o.put("dup", dup);
        o.put("errors", errors);
        return o;
    }
}
