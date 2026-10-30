"""End-to-end test of our own Android app against the private cloud test API.

Credentials are loaded from a local private configuration, never printed.
Only the demo namespace is changed; real Chaoxing assignments remain read-only.
"""
import json
import shutil
import pathlib
import re
import subprocess
import time
import urllib.request

ROOT = next(p for p in pathlib.Path(__file__).resolve().parents if (p / 'work/tools/android-sdk').is_dir())
ADB = ROOT / 'work/tools/android-sdk/platform-tools/adb.exe'
DEVICE = 'emulator-5556'
PACKAGE = 'cn.liuzf.lighthouse'
EVIDENCE = ROOT / 'outputs/作业灯塔/evidence'
CONFIG = json.loads((ROOT / 'work/private/test-connection.json').read_text(encoding='utf-8-sig'))
results = []


def adb(*args, timeout=30):
    proc = subprocess.run([str(ADB), '-s', DEVICE, *args], capture_output=True, timeout=timeout)
    if proc.returncode:
        # Never include command arguments: configuration calls contain the API token.
        raise RuntimeError('ADB command failed: ' + proc.stderr.decode('utf-8', 'replace')[:200])
    return proc.stdout.decode('utf-8', 'replace')


def debug(command, **extras):
    args = ['shell', 'am', 'broadcast', '-n', PACKAGE + '/.DebugReceiver', '--es', 'command', command]
    for key, value in extras.items():
        args += ['--es', key, value]
    adb(*args)


def cloud_scenario(name):
    req = urllib.request.Request('http://127.0.0.1:18765/api/v1/demo/scenario', data=json.dumps({'name': name}).encode(), headers={'Authorization':'Bearer ' + CONFIG['token'], 'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def notification():
    data = adb('shell', 'dumpsys', 'notification', '--noredact')
    match = re.search(r'NotificationRecord\([^\n]*pkg=' + re.escape(PACKAGE) + r'[^\n]*\bid=3101\b.*?(?=\n\s*NotificationRecord\(|\n\s*Notification Listeners:|\Z)', data, re.S)
    return match.group(0) if match else ''


def wait_for(predicate, timeout=50, message='condition'):
    end = time.time() + timeout
    while time.time() < end:
        result = predicate()
        if result:
            return result
        time.sleep(1)
    raise AssertionError('Timeout waiting for ' + message)


def cache(demo=True):
    name = 'demo.json' if demo else 'snapshot.json'
    try:
        return json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/' + name))
    except (RuntimeError, json.JSONDecodeError):
        return {}


def screenshot(name):
    data = subprocess.run([str(ADB), '-s', DEVICE, 'exec-out', 'screencap', '-p'], capture_output=True, check=True, timeout=30).stdout
    (EVIDENCE / name).write_bytes(data)


def record(name, **evidence):
    results.append({'test':name,'passed':True, **evidence})
    print('PASS ' + name, flush=True)
    (EVIDENCE / 'android-e2e.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')


PS = shutil.which('pwsh') or shutil.which('powershell')


def restore_transport():
    def healthy():
        try:
            with urllib.request.urlopen('http://127.0.0.1:18765/healthz', timeout=2) as response:
                return response.status == 200
        except Exception:
            return False
    if not healthy():
        start_script = '''
$tunnelKey = Join-Path $env:USERPROFILE '.ssh\\codex_deploy'
$tunnelArguments = @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=3','-o','StrictHostKeyChecking=yes','-i',$tunnelKey,'-L','127.0.0.1:18765:127.0.0.1:18765','deploy@43.143.37.144')
Start-Process -FilePath 'ssh.exe' -ArgumentList $tunnelArguments -WindowStyle Hidden | Out-Null
'''
        subprocess.run([PS, '-NoProfile', '-Command', start_script], capture_output=True, timeout=20, check=True)
    adb('reverse', 'tcp:18765', 'tcp:18765')
    wait_for(healthy, timeout=20, message='SSH tunnel restored')


def break_transport():
    stop_script = '''
$tunnelOwners = @(Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 18765 -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)
if ($tunnelOwners.Count -ne 1) { throw 'Unexpected tunnel owner count' }
$tunnelProcess = Get-Process -Id $tunnelOwners[0] -ErrorAction Stop
if ($tunnelProcess.ProcessName -ne 'ssh') { throw 'Port owner is not our SSH helper' }
Stop-Process -Id $tunnelProcess.Id -ErrorAction Stop
'''
    subprocess.run([PS, '-NoProfile', '-Command', stop_script], capture_output=True, timeout=30, check=True)


def main():
    EVIDENCE.mkdir(exist_ok=True, parents=True)
    (EVIDENCE / 'android-e2e-error.txt').unlink(missing_ok=True)
    restore_transport()
    adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    debug('configure', endpoint=CONFIG['endpoint'], token=CONFIG['token'])
    real = wait_for(lambda: cache(False).get('items') and cache(False), message='real cloud data')
    active = [x for x in real['items'] if x['category'] == 'active']
    record('真实云端数据接入', total=len(real['items']), active=len(active), demo=real.get('demo'))
    time.sleep(2)
    screenshot('01-真实作业列表.png')

    debug('scenario', name='urgent')
    wait_for(lambda: '3 项作业待交' in notification(), message='three-item urgent notification')
    record('3项紧急作业合并为一条通知', task_notification_id=3101)
    time.sleep(6)  # Let the heads-up banner finish before photographing the app.
    screenshot('02-演示作业列表.png')
    debug('live')
    # The service must remain alive when our Activity is no longer visible.
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    adb('shell', 'cmd', 'statusbar', 'expand-notifications')
    time.sleep(2)
    screenshot('03-紧急作业通知.png')
    before = notification()
    assert '稍后 30 分钟' in before and '立即同步' in before
    record('通知含查看、稍后提醒和同步操作')

    start = time.time()
    cloud_scenario('submitted_one')
    wait_for(lambda: '2 项作业待交' in notification(), message='background removal of submitted item')
    record('退到后台后自动移除已提交项', elapsed_seconds=round(time.time()-start,1), remaining=2)
    screenshot('04-提交一项后通知更新.png')

    start = time.time()
    cloud_scenario('submitted_all')
    wait_for(lambda: not notification(), message='automatic withdrawal after all submitted')
    detected = round(time.time() - start, 1)
    time.sleep(8)
    assert not notification(), 'A stale background Activity must not resurrect a completed notification'
    record('全部提交后自动撤回任务通知', elapsed_seconds=detected, remained_cancelled_seconds=8)
    screenshot('05-全部提交后自动撤回.png')

    cloud_scenario('urgent')
    wait_for(lambda: '3 项作业待交' in notification(), message='new urgent items')
    cloud_scenario('expired')
    wait_for(lambda: not notification(), message='expired withdrawal')
    time.sleep(8)
    assert not notification(), 'An expired notification must stay cancelled'
    record('全部截止后停止催促')

    debug('scenario', name='urgent')
    wait_for(lambda: bool(notification()), message='notification before snooze')
    debug('snooze')
    wait_for(lambda: not notification(), message='snooze')
    debug('sync')
    time.sleep(2)
    assert not notification()
    record('稍后30分钟期间同步不会重新催促')

    debug('scenario', name='urgent')
    wait_for(lambda: bool(notification()), message='notification before outage')
    cached_before = cache()
    adb('logcat', '-c')
    break_transport()
    debug('sync')
    wait_for(lambda: 'network_error=true' in adb('logcat', '-d', '-s', 'LighthouseTest:I'), timeout=40, message='actual failed network request')
    facts = lambda data: [(r['id'], r['category'], r.get('deadline_at'), r['raw_status']) for r in data.get('items', [])]
    assert facts(cache()) == facts(cached_before)
    assert notification()
    record('网络失败保留缓存和提醒，不误判已提交')
    restore_transport()
    debug('sync')
    wait_for(lambda: cache().get('server_time', 0) > cached_before.get('server_time', 0), message='network recovery')
    record('网络恢复后继续同步')

    debug('stop_live')
    wait_for(lambda: 'LiveSyncService' not in adb('shell', 'dumpsys', 'activity', 'services', PACKAGE), message='foreground service stopped')
    debug('scenario', name='urgent')
    wait_for(lambda: bool(notification()), message='notification before system worker test')
    cloud_scenario('submitted_all')
    debug('worker_sync')
    wait_for(lambda: not notification(), message='WorkManager withdrawal without foreground service')
    record('没有实时服务时WorkManager仍可执行同步并撤回通知', trigger='通知立即同步操作对应的系统后台任务')

    # Return the app to real data after the test; do not leave demo assignments visible.
    debug('stop_live')
    debug('real')
    wait_for(lambda: not notification(), message='real tasks outside default urgency window')
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
    time.sleep(2)
    screenshot('06-验证后恢复真实数据.png')
    record('结束演示恢复真实数据')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        (EVIDENCE / 'android-e2e-error.txt').write_text(type(error).__name__ + ': ' + str(error), encoding='utf-8')
        raise
    finally:
        try:
            restore_transport()
        except Exception:
            pass
