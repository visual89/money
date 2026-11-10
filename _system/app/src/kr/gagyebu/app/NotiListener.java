package kr.gagyebu.app;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 폰에 뜨는 알림 중 결제 알림(카드사·은행·네이버·쿠팡·문자 등)을 읽어 가계부에 적는다. */
public class NotiListener extends NotificationListenerService {

    private static long lastSeenSaved;

    /** 알림 접근이 켜져 안드로이드가 이 서비스를 연결했을 때 — 설정 탭의 '알림 수신' 표시에 쓴다 */
    @Override
    public void onListenerConnected() {
        try {
            Db.get(this).put("noti_connected", String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
            // 어떤 앱이든 알림이 들어온 시각만 적어 둔다(내용은 적지 않음, 1분에 한 번) — 알림이 앱까지 오는지 확인용
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSeenSaved > 60000) {
                lastSeenSaved = nowMs;
                Db.get(this).put("noti_seen", String.valueOf(nowMs));
            }
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle e = n.extras;
            if (e == null) return;
            String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = str(e.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) return;

            String label = pkg;
            try {
                PackageManager pm = getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Exception ignored) {
            }
            String result = Db.get(this).capture(pkg, label, title, text, sbn.getPostTime());
            if (result.startsWith("기록") || result.startsWith("중복")) Sync.kick(this, null);   // 적었으면 바로 시트에도 올린다
        } catch (Exception ignored) {
            // 알림 하나를 못 읽어도 서비스가 죽지 않게 한다
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }
}
