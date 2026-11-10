package kr.gagyebu.app;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 폰에 뜨는 알림 중 결제 알림(카드사·은행·네이버·쿠팡·문자 등)을 읽어 가계부에 적는다. */
public class NotiListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
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
