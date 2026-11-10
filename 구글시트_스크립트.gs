/**
 * 가계부 앱 ↔ 구글 스프레드시트 공유 스크립트 (v1.1)
 *
 * 붙이는 법은 '설치 안내.html' 참고. 아래 KEY의 암호만 내 것으로 바꾸고, 두 폰 앱의 설정에 같은 암호를 넣는다.
 * 시트 '가계부'에 내역이 한 줄씩 쌓인다. 고유번호·수정시각·삭제·순번 열은 앱이 쓰는 것이니 고치지 않는다.
 * (시트에서 손으로 고친 내용은 폰에 반영되지 않는다. 고칠 때는 앱에서 고친다.)
 */
const KEY = '여기에_암호를_적으세요';
const SHEET = '가계부';
const HEAD = ['고유번호', '날짜', '시간', '구분', '분류', '금액', '결제수단', '내용', '사용자', '수정시각', '삭제', '순번'];

function sheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET);
  if (!sh) {
    sh = ss.insertSheet(SHEET);
    sh.getRange(1, 1, 1, HEAD.length).setValues([HEAD]).setFontWeight('bold');
    sh.setFrozenRows(1);
    // 날짜·시간이 구글 시트 날짜 값으로 바뀌지 않게 글자 그대로 둔다 (금액만 숫자)
    sh.getRange('A:E').setNumberFormat('@');
    sh.getRange('F:F').setNumberFormat('#,##0');
    sh.getRange('G:I').setNumberFormat('@');
    sh.getRange('J:L').setNumberFormat('0');
  }
  return sh;
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

/** 받기: ?key=암호&since=순번 → 그 순번 뒤로 바뀐 줄 */
function doGet(e) {
  const p = (e && e.parameter) || {};
  if (KEY === '여기에_암호를_적으세요') return out_({ error: '시트 스크립트의 암호(KEY)를 먼저 바꿔 주세요' });
  if (p.key !== KEY) return out_({ error: '암호가 다릅니다' });
  const since = Number(p.since || 0);
  const v = sheet_().getDataRange().getValues();
  const rows = [];
  let max = since;
  for (let i = 1; i < v.length; i++) {
    const r = v[i], rev = Number(r[11]);
    if (!r[0] || !(rev > since)) continue;
    rows.push({
      uid: String(r[0]), date: String(r[1]), time: String(r[2]), kind: r[3] === '수입' ? 'in' : 'out',
      category: String(r[4]), amount: Number(r[5]), method: String(r[6]), memo: String(r[7]), who: String(r[8]),
      updated: Number(r[9]), deleted: Number(r[10]) ? 1 : 0,
    });
    if (rev > max) max = rev;
  }
  return out_({ ok: true, rows: rows, rev: max });
}

/** 올리기: {key, rows:[…]} → 고유번호로 찾아, 더 나중에 고친 쪽을 남긴다 */
function doPost(e) {
  let b;
  try { b = JSON.parse(e.postData.contents); } catch (err) { return out_({ error: '요청을 읽을 수 없습니다' }); }
  if (KEY === '여기에_암호를_적으세요') return out_({ error: '시트 스크립트의 암호(KEY)를 먼저 바꿔 주세요' });
  if (b.key !== KEY) return out_({ error: '암호가 다릅니다' });
  const rows = b.rows || [];
  const lock = LockService.getScriptLock();
  lock.waitLock(25000);
  try {
    const sh = sheet_();
    const last = sh.getLastRow();
    const data = last > 1 ? sh.getRange(2, 1, last - 1, HEAD.length).getValues() : [];
    const at = {};
    let rev = Date.now();
    data.forEach(function (r, i) { at[String(r[0])] = i; if (Number(r[11]) >= rev) rev = Number(r[11]) + 1; });
    let saved = 0;
    const added = [];
    rows.forEach(function (r) {
      if (!r.uid) return;
      const line = [String(r.uid), String(r.date || ''), String(r.time || ''), r.kind === 'in' ? '수입' : '지출', String(r.category || ''),
        Number(r.amount) || 0, String(r.method || ''), String(r.memo || ''), String(r.who || ''), Number(r.updated) || 0, r.deleted ? 1 : 0, rev];
      const i = at[line[0]];
      if (i === undefined) { at[line[0]] = data.length + added.length; added.push(line); saved++; }
      else if (i >= data.length) { if (line[9] >= added[i - data.length][9]) added[i - data.length] = line; }
      else if (line[9] > Number(data[i][9])) { data[i] = line; sh.getRange(i + 2, 1, 1, HEAD.length).setValues([line]); saved++; }
    });
    if (added.length) sh.getRange(data.length + 2, 1, added.length, HEAD.length).setValues(added);
    return out_({ ok: true, saved: saved, rev: rev });
  } finally {
    lock.releaseLock();
  }
}
