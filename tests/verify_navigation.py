import json, pathlib, subprocess, time, re, xml.etree.ElementTree as ET
ROOT=next(p for p in pathlib.Path(__file__).resolve().parents if (p/'work/tools/android-sdk').is_dir())
ADB=ROOT/'work/tools/android-sdk/platform-tools/adb.exe'
PKG='cn.liuzf.lighthouse'
OUT=ROOT/'outputs/作业灯塔/evidence'
results=[]
def adb(*args):
 p=subprocess.run([str(ADB),'-s','emulator-5556',*args],capture_output=True,timeout=45)
 if p.returncode: raise RuntimeError('adb failed')
 return p.stdout

def tree():

 for attempt in range(3):
  try:
   result=adb('shell','uiautomator','dump','/sdcard/lighthouse-ui.xml')
   if b'dumped' not in result: raise RuntimeError('UI not idle')
   break
  except RuntimeError:
   time.sleep(1)
 return ET.fromstring(adb('shell','cat','/sdcard/lighthouse-ui.xml').decode())
def tap(label):
 for n in tree().iter('node'):
  if label in (n.get('text'),n.get('content-desc')):
   x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
   adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); time.sleep(1); return
 raise AssertionError('Missing control: '+label)
def expect(label):
 assert any(label in (n.get('text','')+' '+n.get('content-desc','')) for n in tree().iter('node')),label

def screenshot(name):
 (OUT/name).write_bytes(adb('exec-out','screencap','-p'))

def record(name):
 results.append({'case':name,'passed':True}); (OUT/'guidance-e2e.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8'); print('PASS',name,flush=True)


results=[]
def record(name):
 results.append({'case':name,'passed':True}); (OUT/'navigation-e2e.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8'); print('PASS',name,flush=True)
def absent(label):
 assert not any(label in (n.get('text','')+' '+n.get('content-desc','')) for n in tree().iter('node')),label

def scroll_to(label):
 for _ in range(8):
  if any(n.get('text')==label for n in tree().iter('node')): return
  adb('shell','input','swipe','540','1750','540','700','350'); time.sleep(.5)
 raise AssertionError('Not reachable: '+label)

adb('shell','am','force-stop',PKG); adb('shell','am','start','-n',PKG+'/.MainActivity'); time.sleep(5)
expect('当前待办'); expect('作业'); expect('提醒'); expect('我的'); absent('主题配色'); absent('实时模式'); screenshot('16-v030-work.png'); record('底部三项导航，作业页只含作业内容')
tap('已完成'); expect('已提交 / 已完成'); record('作业状态是页内筛选')
tap('提醒'); expect('提醒概览'); expect('提前提醒时间'); absent('已提交 / 已完成'); absent('把要紧的事'); screenshot('17-v030-reminders.png'); record('提醒导航切换完整页面')
tap('提前提醒时间'); expect('提前小时数（1～168）'); record('提醒时间独立设置，不要求云端验证')
field=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText'); x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')))
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); adb('shell','input','keycombination','113','29'); adb('shell','input','text','72'); adb('shell','input','keyevent','4'); tap('保存'); expect('截止前 72 小时'); record('修改提醒阈值立即生效')
# Restore previous default to avoid changing the user's existing reminder behavior.
tap('提前提醒时间'); field=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText'); x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')))
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); adb('shell','input','keycombination','113','29'); adb('shell','input','text','24'); adb('shell','input','keyevent','4'); tap('保存')
tap('我的'); expect('账号与连接'); expect('主题配色'); expect('通知与后台运行'); absent('提醒概览'); absent('当前待办'); screenshot('18-v030-mine.png'); record('我的页面集中连接、应用和系统设置')
tap('云端连接'); expect('访问令牌'); absent('提前小时数'); tap('取消'); record('云端连接表单只管理连接')
tap('主题配色'); tap('晴日绿 · 浅色'); tap('完成'); expect('账号与连接'); screenshot('19-v030-mine-light.png'); record('修改主题后仍留在我的页面')
tap('主题配色'); tap('薄荷绿 · 深色'); tap('完成')
tap('通知与后台运行'); expect('通知与紧急作业渠道'); tap('完成'); record('系统设置入口迁移后可用')
scroll_to('关于作业灯塔'); tap('关于作业灯塔'); expect('0.3.0-test'); tap('知道了'); record('关于显示当前版本')
scroll_to('隐私与数据说明'); tap('隐私与数据说明'); expect('Keystore'); tap('知道了'); record('隐私说明可访问')
adb('shell','input','keyevent','4'); time.sleep(1); expect('已提交 / 已完成'); record('返回键回到作业且保留筛选状态')
tap('待完成'); expect('当前待办'); screenshot('20-v030-release-home.png'); print('COMPLETE',len(results),flush=True)
