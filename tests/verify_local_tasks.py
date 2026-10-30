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
 results.append({'case':name,'passed':True}); (OUT/'tasks-e2e.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8'); print('PASS',name,flush=True)
def notice():
 data=adb('shell','dumpsys','notification','--noredact').decode('utf-8','replace')
 match=re.search(r'NotificationRecord\([^\n]*pkg='+PKG+r'[^\n]*\bid=3101\b.*?(?=\n\s*NotificationRecord\(|\n\s*Notification Listeners:|\Z)',data,re.S)
 return match.group(0) if match else ''
def choose(label):
 for _ in range(12):
  if any(n.get('text')=='作业灯塔' for n in tree().iter('node')): break
  adb('shell','input','swipe','540','600','540','1900','250')
 for _ in range(5):
  nodes=list(tree().iter('node'))
  for n in nodes:
   if n.get('text')==label:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    if x2>x1 and y2>y1: tap(label); return
  anchor=next(n for n in nodes if n.get('text') in ('待完成','重点','已截止','已完成','已忽略','其他') and n.get('bounds')!='[0,0][0,0]')
  y1,y2=map(int,[re.findall(r'\d+',anchor.get('bounds'))[1], re.findall(r'\d+',anchor.get('bounds'))[3]])
  adb('shell','input','swipe','940',str((y1+y2)//2),'150',str((y1+y2)//2),'350'); time.sleep(.5)
 raise AssertionError('Missing filter '+label)
def restart():
 adb('shell','am','force-stop',PKG); adb('shell','am','start','-n',PKG+'/.MainActivity'); time.sleep(5)

def scroll_find(label):
 for _ in range(15):
  if any(label in n.get('text','') for n in tree().iter('node')): return
  adb('shell','input','swipe','540','1700','540','800','300'); time.sleep(.5)
 raise AssertionError('Missing task '+label)

restart(); tap('添加手动任务'); expect('未设置截止时间')
field=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText'); x1,y1,x2,y2=map(int,re.findall(r'\d+',field.get('bounds')))
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)); adb('shell','input','text','ManualTaskCheck'); adb('shell','input','keyevent','4'); tap('添加'); record('无需截止时间可创建手动任务')
scroll_find('ManualTaskCheck'); tap('任务操作：ManualTaskCheck'); tap('提上日程'); choose('重点'); expect('ManualTaskCheck'); assert 'ManualTaskCheck' in notice(); screenshot('21-v040-focused.png'); record('提上日程进入重点列表并显示通知')
restart(); choose('重点'); expect('ManualTaskCheck'); assert 'ManualTaskCheck' in notice(); record('手动任务与重点在重启后保留')
tap('任务操作：ManualTaskCheck'); tap('忽略不管'); assert 'ManualTaskCheck' not in notice(); record('忽略立即停止通知')
choose('已忽略'); expect('ManualTaskCheck'); screenshot('22-v040-ignored.png'); restart(); choose('已忽略'); expect('ManualTaskCheck'); record('忽略列表与状态持久保存')
tap('任务操作：ManualTaskCheck'); tap('恢复关注'); choose('重点'); expect('ManualTaskCheck'); assert 'ManualTaskCheck' in notice(); record('恢复关注可重新进入重点提醒')
tap('任务操作：ManualTaskCheck'); tap('移出重点'); assert 'ManualTaskCheck' not in notice(); record('无截止任务移出重点后停止提醒')
choose('待完成'); scroll_find('ManualTaskCheck'); tap('任务操作：ManualTaskCheck'); tap('提上日程'); choose('重点'); tap('任务操作：ManualTaskCheck'); tap('标记完成'); assert 'ManualTaskCheck' not in notice(); record('手动标记完成后从通知移除')
choose('已完成'); scroll_find('ManualTaskCheck'); expect('ManualTaskCheck'); record('手动完成任务进入已完成列表')
# A cloud refresh cannot overwrite the local task. No cloud assignment is modified.
adb('shell','input','swipe','540','650','540','1900','300'); time.sleep(.5)
restart(); tap('刷新作业'); time.sleep(5); choose('已完成'); scroll_find('ManualTaskCheck'); record('云端刷新不覆盖手动任务')
restart(); print('COMPLETE',len(results),flush=True)
