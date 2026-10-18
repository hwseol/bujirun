"""The privacy policy page (GET /privacy), for the Play listing and Google's OAuth branding.

Written from what the app and this server actually do (see the app's data stores and the
endpoints in server.py); keep it in step when that changes. The contact address comes from the
server's BUJIRUN_ADMIN_EMAIL, so it isn't in this public repository.
"""

import html
import os

UPDATED = "2026-10-18"

KO = """
<h1>부지런 개인정보처리방침</h1>
<p class="meta">시행일 {updated}</p>
<p>부지런(Buji Run, 이하 "앱")은 달리기·걷기 기록 앱입니다. 앱은 운동 기록을 <b>사용자의 휴대폰에만</b>
저장하며, 서버에는 로그인에 필요한 최소한의 계정 정보만 보관합니다.</p>

<h2>1. 서버에 보관하는 정보</h2>
<table>
<tr><th>항목</th><th>목적</th><th>보관 기간</th></tr>
<tr><td>이메일, 닉네임</td><td>로그인, 계정 구분</td><td>계정 삭제 시까지</td></tr>
<tr><td>비밀번호 (복원할 수 없는 해시로만 저장)</td><td>로그인</td><td>계정 삭제 시까지</td></tr>
<tr><td>로그인 세션 토큰</td><td>로그인 유지</td><td>로그아웃 또는 180일</td></tr>
<tr><td>비밀번호 재설정 코드 (해시)</td><td>비밀번호 찾기</td><td>사용 즉시 또는 15분 후 무효</td></tr>
</table>
<p>구글로 로그인하면 구글이 확인한 이메일과 이름(닉네임으로 사용)만 받습니다. 구글 계정의 다른 정보에는 접근하지 않습니다.</p>

<h2>2. 휴대폰에만 저장하는 정보 (서버로 보내지 않음)</h2>
<ul>
<li>운동 기록: 경로(GPS 위치), 시간, 거리, 페이스, 칼로리, 걸음 수</li>
<li>자동 기록(켜 둔 경우): 걷기·달리기 활동, 하루 걸음 수 — 신체 활동 인식과 걸음 센서를 사용하며 GPS는 쓰지 않습니다</li>
<li>내 코스, 체중(칼로리 계산용), 앱 설정, 프로필 사진</li>
<li>운동 사진: 사용자가 고른 사진을 운동 경로 위에 보여 줄 뿐, 사진을 업로드하지 않습니다</li>
</ul>
<p>이 정보는 안드로이드의 기본 백업 기능을 켠 경우 사용자의 구글 계정 백업(구글 드라이브)에 함께 백업될 수 있으며,
앱 개발자는 이 백업에 접근할 수 없습니다.</p>

<h2>3. 서버로 보내지만 저장하지 않는(또는 익명으로만 저장하는) 정보</h2>
<ul>
<li>코스 추천·경로 찾기·장소 검색 시: 출발 위치와 지점 좌표, 검색어. 같은 요청을 빨리 처리하기 위해 결과(경로, 주변 지명)를 위치 기준으로 최대 60일 캐시하며, 계정이나 사용자와 연결하지 않습니다.</li>
<li>서버 접속 기록(IP 주소, 요청 시각, 요청 주소): 보안과 장애 대응을 위해 일정 기간 보관 후 자동 삭제됩니다.</li>
</ul>

<h2>4. 제3자 제공 및 처리 위탁</h2>
<ul>
<li><b>SK텔레콤 TMAP</b>: 걷기 경로 계산을 위해 지점 좌표를 전달합니다 (계정 정보는 전달하지 않음).</li>
<li><b>네이버 클라우드(네이버 지도)</b>: 지도 표시. 지도 SDK가 지도 이미지를 내려받습니다.</li>
<li><b>Google</b>: 구글 로그인, 비밀번호 재설정 메일 발송(Gmail).</li>
<li><b>Amazon Web Services</b>: 서버 운영(미국 버지니아 리전). 계정 정보가 이곳에 저장됩니다.</li>
</ul>
<p>법령에 따른 경우를 제외하고 개인정보를 판매하거나 광고 목적으로 제공하지 않습니다.</p>

<h2>5. 사용자가 켤 때만 하는 일</h2>
<ul>
<li>구글 캘린더 기록: 운동 결과를 사용자의 캘린더에 일정으로 추가합니다.</li>
<li>삼성헬스/Health Connect 저장: 운동과 경로를 사용자의 Health Connect에 기록하고, 하루 걸음 수를 읽어 옵니다.</li>
</ul>
<p>이 기능은 해당 권한을 허용한 경우에만 동작하며, 데이터는 사용자의 기기와 계정 안에서만 오갑니다.</p>

<h2>6. 권한</h2>
<p>위치(운동 기록), 알림(운동 중 안내), 신체 활동 인식(걸음·자동 기록), 캘린더와 Health Connect(사용자가 켠 경우)를 사용합니다. 사진은 사용자가 시스템 사진 선택기로 고른 것만 볼 수 있으며 사진 접근 권한은 요청하지 않습니다. 거부해도 해당 기능 외에는 쓸 수 있습니다.</p>

<h2>7. 계정 삭제와 데이터 삭제</h2>
<ul>
<li>앱의 설정 → 내 계정 → <b>계정 삭제</b>에서 바로 삭제할 수 있으며, 서버의 계정 정보와 세션이 즉시 지워집니다.</li>
<li>휴대폰의 운동 기록은 앱의 기록 화면에서 삭제하거나, 앱을 지우면 함께 삭제됩니다.</li>
<li>앱을 쓸 수 없는 경우 아래 연락처로 요청하면 계정을 삭제해 드립니다.</li>
</ul>

<h2>8. 아동</h2>
<p>앱은 만 14세 미만 아동을 대상으로 하지 않습니다.</p>

<h2>9. 문의</h2>
<p>개인정보 관련 문의: {contact}</p>
<p>방침이 바뀌면 이 페이지와 앱 공지로 알립니다.</p>
"""

EN = """
<h1>Buji Run Privacy Policy</h1>
<p class="meta">Effective {updated}</p>
<p>Buji Run (부지런) is a running and walking app. Workouts are stored <b>only on your phone</b>; the
server keeps only what logging in needs.</p>
<h2>Kept on the server</h2>
<p>Email, nickname, a one-way hash of your password, login session tokens (until you log out or
180 days), and password-reset codes (hashed, void after use or 15 minutes). With Google sign-in
we receive only your verified email and name.</p>
<h2>Only on your phone</h2>
<p>Workouts (GPS route, time, distance, pace, calories, steps), auto-recorded walks and daily
steps (activity recognition and the step sensor, no GPS), your courses, weight, settings and
profile photo. Workout photos are the ones you pick with the system photo picker (no photo
permission is asked), shown on the route and never uploaded. If Android
backup is on, this data may be included in your own Google Drive backup, which we can't access.</p>
<h2>Sent but not kept with your account</h2>
<p>For course suggestions, routes and place search: start and point coordinates and search
words. Results (routes, nearby place names) are cached by location for up to 60 days, not tied
to your account or to you. Server access logs (IP, time, path) are kept for a limited time for security.</p>
<h2>Service providers</h2>
<p>SK Telecom TMAP (walking routes: coordinates only), NAVER Cloud (maps), Google (sign-in,
reset emails via Gmail), Amazon Web Services (servers, US East). We don't sell personal data or
use it for advertising.</p>
<h2>Only when you turn it on</h2>
<p>Google Calendar (adds your workouts as events) and Health Connect / Samsung Health (writes
workouts and routes, reads daily steps).</p>
<h2>Deleting</h2>
<p>Settings → My account → Delete account removes your account from the server at once. Workouts
on the phone are deleted in the app or by uninstalling it. You can also ask us at the address
below.</p>
<h2>Children</h2><p>The app is not directed at children under 14.</p>
<h2>Contact</h2><p>{contact}</p>
"""

STYLE = """
body{font-family:-apple-system,"Noto Sans KR","Malgun Gothic",sans-serif;max-width:760px;margin:0 auto;padding:24px 18px 64px;line-height:1.7;color:#222}
h1{font-size:1.6em;margin-bottom:0}h2{font-size:1.15em;margin-top:2em;color:#ff4d4d}
.meta{color:#888;margin-top:4px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;padding:6px 8px;text-align:left;font-size:.95em}
th{background:#fff4ef}hr{margin:48px 0;border:none;border-top:1px solid #eee}
"""


def page() -> str:
    email = os.environ.get("BUJIRUN_ADMIN_EMAIL", "").strip()
    contact = f'<a href="mailto:{html.escape(email)}">{html.escape(email)}</a>' if email else "앱 내 공지 참고"
    body = KO.format(updated=UPDATED, contact=contact) + "<hr>" + EN.format(updated=UPDATED, contact=contact)
    return (f'<!doctype html><html lang="ko"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width, initial-scale=1">'
            f"<title>부지런 개인정보처리방침</title><style>{STYLE}</style></head><body>{body}</body></html>")
