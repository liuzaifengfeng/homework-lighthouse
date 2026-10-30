"""Read-only course/work LIST client; never opens work/submit or work/dowork."""
import base64
import http.cookiejar
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from bs4 import BeautifulSoup
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from classification import classify


class LoginRequired(Exception):
    pass


def soup(source):
    return BeautifulSoup(source, 'html.parser')


def inputs(page):
    return {tag.get('name') or tag.get('id'): tag.get('value', '') for tag in page.select('input')}


class ChaoxingClient:
    def __init__(self, username, password):
        self.username, self.password = username, password
        self.logged_in = False
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.opener.addheaders = [('User-Agent', 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36')]

    def request(self, url, data=None, referer='https://passport2.chaoxing.com/', ajax=False):
        parts = urllib.parse.urlsplit(url)
        if parts.scheme != 'https' or not (parts.hostname or '').endswith('.chaoxing.com'):
            raise ValueError('Unexpected destination')
        headers = {'Referer': referer}
        if ajax:
            headers.update({'X-Requested-With': 'XMLHttpRequest', 'Origin': 'https://passport2.chaoxing.com'})
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        with self.opener.open(urllib.request.Request(url, data=body, headers=headers), timeout=25) as response:
            if response.geturl().startswith('https://passport2.chaoxing.com/login') and 'passport2.chaoxing.com/login' not in url:
                self.logged_in = False
                raise LoginRequired('Session expired')
            return response.geturl(), response.read().decode('utf-8', 'replace')

    def login(self):
        import json
        url = 'https://passport2.chaoxing.com/login?refer=https%3A%2F%2Fi.chaoxing.com'
        _, source = self.request(url)
        page = soup(source)
        hidden = inputs(page)
        if hidden.get('needVcode') == '1':
            raise LoginRequired('需要人工验证，请重新授权')
        script = next((x.get('src') for x in page.select('script[src]') if '/fanya/login.js' in x.get('src', '')), None)
        if not script:
            raise LoginRequired('登录页面结构改变')
        _, code = self.request(urllib.parse.urljoin(url, script), referer=url)
        match = re.search(r'transferKey\s*=\s*[\"\x27]([^\"\x27]+)', code)
        if not match:
            raise LoginRequired('登录参数读取失败')
        key = match.group(1).encode()

        def encrypted(value):
            pad = padding.PKCS7(128).padder()
            value = pad.update(value.encode()) + pad.finalize()
            enc = Cipher(algorithms.AES(key), modes.CBC(key)).encryptor()
            return base64.b64encode(enc.update(value) + enc.finalize()).decode()

        fields = {k: hidden.get(k, '') for k in ('fid', 'refer', 't', 'forbidotherlogin', 'validate', 'doubleFactorLogin', 'independentId', 'independentNameId')}
        fields.update(uname=encrypted(self.username), password=encrypted(self.password))
        _, source = self.request('https://passport2.chaoxing.com/fanyalogin', fields, url, True)
        fields.clear()
        result = json.loads(source)
        if not result.get('status') or result.get('containTwoFactorLogin'):
            raise LoginRequired('登录失败或需要人工验证')
        if result.get('url'):
            self.request(urllib.parse.urljoin(url, urllib.parse.unquote(result['url'])), referer=url)
        self.logged_in = True

    def read(self):
        if not self.logged_in:
            self.login()
        base_url = 'https://i.chaoxing.com/base?ws=1&vflag=true&fid=&backUrl='
        _, source = self.request(base_url)
        home = soup(source)
        menu = home.find(attrs={'dataurl': re.compile(r'mooc2-ans\.chaoxing\.com/visit/interaction')})
        if menu is None:
            self.logged_in = False
            raise LoginRequired('未找到课程入口，请检查登录状态')
        course_url, source = self.request(menu['dataurl'], referer=base_url)
        page = soup(source)
        hidden = inputs(page)
        fields = dict(courseType='1', courseFolderId='0', query='', pageHeader=hidden.get('stuPageHeader', '0'), single=hidden.get('single', '0'), superstarClass=hidden.get('superstarClass', '0'), isFirefly=hidden.get('isFirefly', '0'), fid=hidden.get('filterFid', ''), **{'from': hidden.get('from', '')})
        _, source = self.request(urllib.parse.urljoin(course_url, '/mooc2-ans/visit/courselistdata'), fields, course_url)
        listing = soup(source)
        courses = {}
        for link in listing.select('a[href*="stucoursemiddle"]'):
            url = urllib.parse.urljoin(course_url, link['href'])
            name = link.get_text(' ', strip=True)
            courses[url] = name or courses.get(url, '')
        if not courses and '暂无课程' not in listing.get_text():
            raise ValueError('课程列表结构无法识别')
        rows, results = [], []
        for url, name in courses.items():
            ids = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
            course_id, class_id = ids['courseid'][0], ids['clazzid'][0]
            result = dict(course_id=course_id, class_id=class_id, course_name=name, success=False)
            try:
                page_url, source = self.request(url, referer=course_url)
                page = soup(source)
                hidden = inputs(page)
                entry = page.find('a', attrs={'title': '作业'})
                if not entry or not entry.get('data-url'):
                    raise ValueError('No work menu')
                params = dict(courseId=hidden['courseid'], classId=hidden['clazzid'], cpi=hidden['cpi'], ut='s', t=hidden['t'], stuenc=hidden['enc'], enc=hidden['workEnc'])
                work_url = entry['data-url'] + '?' + urllib.parse.urlencode(params)
                _, source = self.request(work_url, referer=page_url)
                work = soup(source)
                if '作业列表' not in work.get_text() or ('暂无作业' not in work.get_text() and work.select_one('.bottomList') is None):
                    raise ValueError('作业列表解析失败')
                heading = work.select_one('h2')
                class_name = heading.get_text(' ', strip=True) if heading else ''
                observed = int(time.time())
                for li in work.select('li[onclick*="goTask"]'):
                    title, status = li.select_one('p.overHidden2'), li.select_one('p.status')
                    # Missing fields are parsing errors, never evidence of expiry.
                    if title is None or status is None:
                        raise ValueError('Incomplete assignment row')
                    work_ids = urllib.parse.parse_qs(urllib.parse.urlsplit(li.get('data', '')).query)
                    work_id = work_ids.get('workId', [''])[0]
                    if not work_id:
                        raise ValueError('Missing work ID')
                    clock = li.select_one('.time')
                    time_text = clock.get_text(' ', strip=True) if clock else ''
                    raw = status.get_text(' ', strip=True)
                    rows.append(dict(id=f'{course_id}:{class_id}:{work_id}', work_id=work_id, course_id=course_id, class_id=class_id, course_name=name, class_name=class_name, title=title.get_text(' ', strip=True), raw_status=raw, time_text=time_text, observed_at=observed, **classify(raw, time_text, observed)))
                # Paging is explicitly checked, rather than silently claiming full coverage.
                pages = re.search(r'(?:totalPage|totalPages|pageCount)[\"\x27]?\s*[:=]\s*[\"\x27]?(\d+)', source)
                result.update(success=True, pagination_incomplete=bool(pages and int(pages.group(1)) > 1))
            except LoginRequired:
                raise
            except Exception as error:
                # Exception messages may contain signed URLs. Keep only their types.
                result['error_type'] = type(error).__name__
                rows = [r for r in rows if (r['course_id'], r['class_id']) != (course_id, class_id)]
            results.append(result)
            time.sleep(0.15)
        return rows, results
