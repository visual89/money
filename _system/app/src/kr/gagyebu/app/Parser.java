package kr.gagyebu.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결제 알림 문구에서 금액·사용처를 뽑는다. 안드로이드 클래스를 쓰지 않아 PC에서도 그대로 시험할 수 있다.
 * 카드사·은행마다 문구가 달라 규칙은 '느슨하게 뽑고, 틀리면 앱에서 고친다'가 원칙이다.
 */
public final class Parser {
    private Parser() {}

    public static final class Result {
        public boolean ok;
        public String kind = "out";      // out(지출) / in(수입)
        public long amount;
        public String merchant = "";
        public String method = "카드";
        public String reason = "";       // 기록하지 않은 까닭
    }

    private static final Pattern AMT = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원");
    // 알림을 보낸 앱 이름에 이 말이 있으면 결제 알림 후보로 본다
    private static final Pattern FIN = Pattern.compile(
            "카드|페이|은행|뱅크|금고|증권|네이버|NAVER|쿠팡|토스|월렛|Wallet|Pay|BANK|디지로카|로카|LOCA|우리WON|농협", Pattern.CASE_INSENSITIVE);
    // 문자·메신저 앱은 대화가 섞이므로 더 엄격하게 본다
    private static final Pattern MSG = Pattern.compile("메시지|Messages|문자|카카오톡|KakaoTalk", Pattern.CASE_INSENSITIVE);
    private static final Pattern MSG_ACT = Pattern.compile("승인|출금|입금|결제");
    private static final Pattern MSG_WHO = Pattern.compile("카드|페이|은행|뱅크|Web발신|체크|농협|NH|신협|새마을|우체국");
    private static final Pattern PAY = Pattern.compile("승인|결제|출금|사용|이용|구매|주문|체크");
    private static final Pattern NEG = Pattern.compile(
            "\\(광고\\)|\\[광고\\]|거절|실패|예정|명세서|인증번호|본인확인|청구|결제일|한도초과|이벤트|혜택|쿠폰|응모|당첨");
    private static final Pattern SKIP_BEFORE = Pattern.compile("누적|잔액|잔여|한도|포인트|적립|할인|쿠폰");
    // 사용처를 고를 때 지워 낼 말들
    private static final String[] STRIP = {
            "\\[[^\\]]*\\]", "\\([^)]*\\)",
            "(?:누적|잔액|잔여|한도)[^\\n]*",
            "-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원",
            "\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}", "\\d{1,2}/\\d{1,2}", "\\d{1,2}월\\s*\\d{1,2}일",
            "\\d{1,2}:\\d{2}(?::\\d{2})?",
            "\\d+개월", "일시불", "할부", "해외승인", "승인취소", "승인", "취소",
            // 문장형 알림(네이버·쿠팡 등)의 꾸밈말
            "주문하신|구매하신|결제하신|구매확정|주문완료|결제금액|결제완료|총\\s*금액|스마트스토어|상품[의이을]?(?=\\s|$)",
            "결제[가를]?", "완료", "출금", "입금", "사용", "이용", "체크", "구매", "주문",
            "되었습니다|됐습니다|하였습니다|했습니다|입니다|습니다|되었어요|됐어요|했어요",
            "\\S*\\*\\S*", "\\S+님",
            "\\S*카드\\S*", "[가-힣A-Za-z]{2,6}\\d{4}",
            "[\\d*]{4,}",
    };
    private static final Pattern PG = Pattern.compile("네이버페이|NAVER|네이버|쿠팡|카카오페이|토스|페이코|PAYCO|KG이니시스|이니시스|나이스|NICE|다날",
            Pattern.CASE_INSENSITIVE);

    /** 이 앱(이름 label)이 보낸 알림을 결제 알림 후보로 볼지 */
    public static boolean acceptSource(String label, String title, String text) {
        label = nz(label);
        if (MSG.matcher(label).find()) {
            // 가족·지인 대화("카드 결제 3만원 했어")가 적히지 않게: 웹 발신 문자이거나, 보낸 이가 전화번호이거나,
            // 보낸 이 이름에 카드·은행 등이 들어 있을 때만 (카카오톡 알림톡은 보낸 이가 'OO카드')
            String ti = nz(title), tx = nz(text);
            boolean official = tx.contains("Web발신") || ti.matches("[\\d\\-+ ()]{4,}") || MSG_WHO.matcher(ti).find();
            return official && MSG_ACT.matcher(ti + " " + tx).find();
        }
        return FIN.matcher(label).find();
    }

    public static Result parse(String label, String title, String text) {
        Result r = new Result();
        label = nz(label);
        title = nz(title);
        text = nz(text);
        String all = title + "\n" + text;

        if (NEG.matcher(all).find()) { r.reason = "광고·안내 문구"; return r; }
        boolean income = all.contains("입금") && !all.contains("승인");
        boolean cancel = all.contains("취소");
        if (!income && !PAY.matcher(all).find()) { r.reason = "결제 문구 아님"; return r; }

        // 금액: 누적·잔액 등 뒤에 붙은 금액은 건너뛰고 처음 나오는 금액
        Matcher m = AMT.matcher(all);
        long amount = 0;
        while (m.find()) {
            String before = all.substring(Math.max(0, m.start() - 8), m.start());
            int nl = before.lastIndexOf('\n');
            if (nl >= 0) before = before.substring(nl + 1);
            if (SKIP_BEFORE.matcher(before).find()) continue;
            try {
                amount = Long.parseLong(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                amount = 0;
            }
            if (amount > 0) break;
        }
        if (amount <= 0) { r.reason = "금액 없음"; return r; }

        r.ok = true;
        r.amount = amount;
        r.kind = (income || cancel) ? "in" : "out";
        r.merchant = merchant(text);
        if (r.merchant.isEmpty()) r.merchant = merchant(title);
        if (r.merchant.isEmpty()) r.merchant = label;
        if (cancel) r.merchant = (r.merchant + " (결제 취소)").trim();

        String src = label + " " + title;
        if (all.contains("승인") || all.contains("체크카드")) r.method = "카드";
        else if (src.contains("은행") || src.contains("뱅크") || all.contains("출금") || income) r.method = "계좌이체";
        else if (src.contains("카드")) r.method = "카드";
        else if (src.contains("페이") || src.toLowerCase().contains("pay")) r.method = "간편결제";
        return r;
    }

    /** 문구에서 금액·날짜·카드 이름 등을 지우고 남는 가장 긴 덩어리를 사용처로 본다 */
    static String merchant(String text) {
        String s = nz(text);
        for (String p : STRIP) s = s.replaceAll(p, "\n");
        String best = "";
        for (String part : s.split("[\\n\\r]+|\\s{2,}")) {
            String t = part.replaceAll("^[\\s\\-:·,./|→]+|[\\s\\-:·,./|→]+$", "").replaceAll("(에서|에게|으로)$", "").trim();
            if (t.length() < 2 || !t.matches(".*[가-힣A-Za-z].*")) continue;
            if (t.length() > best.length()) best = t;
        }
        return best.length() > 40 ? best.substring(0, 40) : best;
    }

    /** 사용처가 결제 대행사(네이버페이·쿠팡 등) 이름뿐인지 — 같은 결제의 다른 알림에 진짜 상호가 있으면 그것을 쓴다 */
    public static boolean isGateway(String merchant) {
        String m = nz(merchant);
        return m.isEmpty() || PG.matcher(m).replaceAll("").replaceAll("[\\s()결제취소]", "").isEmpty();
    }

    private static final String[][] CATS = {
            {"카페·간식", "스타벅스|커피|카페|투썸|이디야|메가엠지씨|메가MGC|컴포즈|빽다방|폴바셋|할리스|베이커리|파리바게|뚜레쥬르|던킨|배스킨"},
            {"식비", "배달의민족|배민|요기요|쿠팡이츠|식당|마트|이마트|홈플러스|롯데마트|코스트코|트레이더스|GS25|CU|씨유|세븐일레븐|이마트24|편의점|반찬|정육|치킨|피자|버거|맥도날드|롯데리아|김밥|국밥|한식|분식|마켓컬리|컬리"},
            {"교통", "주유|칼텍스|SK에너지|S-OIL|에쓰오일|오일뱅크|택시|카카오T|버스|지하철|코레일|SRT|티머니|하이패스|주차|톨게이트|도로공사|충전"},
            {"의료·건강", "약국|병원|의원|치과|한의원|내과|외과|안과|이비인후과|피부과|헬스|필라테스"},
            {"통신", "SKT|SK텔레콤|KT|LG유플러스|LGU|알뜰폰|통신"},
            {"문화·여가", "넷플릭스|NETFLIX|유튜브|YOUTUBE|디즈니|티빙|웨이브|왓챠|CGV|메가박스|롯데시네마|교보문고|예스24|알라딘|멜론|스포티파이|여행|호텔|숙박|야놀자|여기어때|항공"},
            {"교육", "학원|교육|학습|유치원|어린이집|문구"},
            {"보험", "보험|생명|화재|손해"},
            {"주거·관리비", "관리비|도시가스|한국전력|한전|수도|월세|아파트"},
            {"의류·미용", "무신사|지그재그|에이블리|유니클로|자라|ZARA|미용|헤어|네일|올리브영|화장품"},
            {"생활용품", "쿠팡|네이버|NAVER|11번가|G마켓|지마켓|옥션|다이소|이케아|오늘의집|SSG|쓱"},
    };

    /** 사용처 이름으로 분류를 짐작한다. 모르면 "미분류" */
    public static String guessCategory(String merchant) {
        String m = nz(merchant);
        for (String[] c : CATS) {
            if (Pattern.compile(c[1], Pattern.CASE_INSENSITIVE).matcher(m).find()) return c[0];
        }
        return "미분류";
    }

    // ───────────── 비슷한 사용처 (v1.6) ─────────────
    private static final Pattern BRANCH = Pattern.compile("\\s+\\S{1,8}점$|\\s*(?:본점|직영점|\\d+호점)$");

    /** 사용처 이름을 견주기 좋게 다듬는다: 괄호·(주)·주식회사·띄어 쓴 지점 이름('강남점')·공백·기호를 빼고 소문자로 */
    public static String normMerchant(String merchant) {
        String s = nz(merchant).replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ").replaceAll("주식회사|유한회사|㈜", " ").trim();
        String cut = BRANCH.matcher(s).replaceAll("");
        if (cut.replaceAll("[^가-힣A-Za-z0-9]", "").length() >= 2) s = cut;
        return s.replaceAll("[^가-힣A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 사용자가 분류를 정해 둔 사용처(known)와 새 사용처(merchant)가 얼마나 비슷한지. 0 = 다른 곳, 클수록 비슷하다.
     * 100 같은 곳(지점·띄어쓰기만 다름) / 80~ 한쪽이 다른 쪽으로 시작('스타벅스' ↔ '스타벅스강남점')
     * / 60~ 한쪽이 다른 쪽에 들어 있음('올리브영' ↔ 'CJ올리브영') / 40~ 앞 네 글자 이상이 같음('스타벅스강남' ↔ '스타벅스역삼').
     * 결제 대행사 이름(네이버페이·쿠팡 등)은 '쿠팡' ↔ '쿠팡이츠'처럼 다른 곳을 묶지 않도록 같은 이름일 때만 인정한다.
     */
    public static int similarity(String known, String merchant) {
        String a = normMerchant(known), b = normMerchant(merchant);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (isGateway(known) || isGateway(merchant)) return 0;
        String s = a.length() <= b.length() ? a : b, l = a.length() <= b.length() ? b : a;
        if (s.length() >= 3 && l.startsWith(s)) return 80 + Math.min(s.length(), 19);
        if (s.length() >= 3 && l.contains(s)) return 60 + Math.min(s.length(), 19);
        int p = 0;
        while (p < s.length() && s.charAt(p) == l.charAt(p)) p++;
        if (p >= 4 && p * 2 >= s.length()) return 40 + Math.min(p, 19);
        return 0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
