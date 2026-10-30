"""Single-user isolated test API. Bind only to localhost; access via SSH tunnel."""
import asyncio
import contextlib
import hmac
import json
import os
import time
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel
from chaoxing import ChaoxingClient, LoginRequired
from store import Store

DATA_DIR = Path(os.environ.get('LIGHTHOUSE_DATA', './data'))
DATA_DIR.mkdir(parents=True, exist_ok=True)
SECRETS_PATH = Path(os.environ.get('LIGHTHOUSE_SECRETS', DATA_DIR / 'secrets.json'))
secrets = json.loads(SECRETS_PATH.read_text()) if SECRETS_PATH.exists() else {}
TOKEN = secrets.get('api_token', os.environ.get('LIGHTHOUSE_TOKEN', ''))
store = Store(DATA_DIR / 'lighthouse.db')
client = ChaoxingClient(secrets.get('username', ''), secrets.get('password', ''))
lock = asyncio.Lock()
sync_task = None
demo = {'scenario': 'urgent', 'version': 1, 'anchor': int(time.time())}


async def synchronize():
    if lock.locked():
        return
    async with lock:
        try:
            rows, courses = await asyncio.to_thread(client.read)
            if courses and not any(c['success'] for c in courses):
                store.failure('all_courses_failed')
            else:
                store.update(rows, courses)
        except LoginRequired:
            store.failure('login_required')
        except Exception as error:
            store.failure(type(error).__name__)


async def scheduler():
    while True:
        await synchronize()
        delay = int(os.environ.get('LIGHTHOUSE_SYNC_SECONDS', '300'))
        # Authentication challenges require user action, never repeated rapid login attempts.
        if store.meta('sync_error') == 'login_required':
            delay = max(delay, 3600)
        await asyncio.sleep(max(60, delay))


@asynccontextmanager
async def lifespan(app):
    if not TOKEN:
        raise RuntimeError('Configure a non-empty API token before starting')
    worker = asyncio.create_task(scheduler()) if secrets.get('username') else None
    yield
    if worker:
        worker.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await worker


app = FastAPI(title='作业灯塔测试服务', lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


def authorized(authorization: str = Header(default='')):
    if not TOKEN or not hmac.compare_digest(authorization, 'Bearer ' + TOKEN):
        raise HTTPException(401, 'Unauthorized')


@app.get('/healthz')
def health():
    return {'status': 'ok', 'service': 'homework-lighthouse'}


@app.get('/api/v1/snapshot', dependencies=[Depends(authorized)])
def snapshot():
    return {**store.snapshot(), 'sync_running': lock.locked(), 'demo': False}


@app.post('/api/v1/sync', status_code=202, dependencies=[Depends(authorized)])
async def trigger():
    global sync_task
    accepted = False
    if not lock.locked() and (time.time() - (store.meta('last_attempt', 0) or 0) > 30):
        sync_task = asyncio.create_task(synchronize())
        accepted = True
    return {'accepted': accepted, 'running': lock.locked() or accepted}


class Scenario(BaseModel):
    name: str


@app.post('/api/v1/demo/scenario', dependencies=[Depends(authorized)])
def set_scenario(request: Scenario):
    if request.name not in {'urgent', 'submitted_one', 'submitted_all', 'expired', 'sync_error', 'restored'}:
        raise HTTPException(400, 'Unknown scenario')
    demo.update(scenario=request.name, version=demo['version'] + 1)
    if request.name in {'urgent', 'restored'}:
        demo['anchor'] = int(time.time())
    return {'scenario': demo['scenario'], 'demo': True}


@app.get('/api/v1/demo/snapshot', dependencies=[Depends(authorized)])
def demo_snapshot():
    now = int(time.time())
    rows = []
    for i, (name, title, seconds) in enumerate([
        ('数字信号处理', '课堂练习 · 还有 10 分钟', 600),
        ('片上系统设计基础', '实验报告 · 今天提交', 7200),
        ('高级电子系统设计', 'FPGA 实验报告', 18000),
    ]):
        category = 'completed' if demo['scenario'] == 'submitted_all' or (demo['scenario'] == 'submitted_one' and i == 0) else ('expired' if demo['scenario'] == 'expired' else 'active')
        rows.append(dict(id=f'demo:{i}', work_id=str(i), course_id='demo', class_id='demo', course_name=name, class_name='通知效果演示', title=title, raw_status='待批阅' if category == 'completed' else '未交', category=category, deadline_at=demo['anchor'] + seconds if category == 'active' else None, observed_at=now, time_text='', classification_basis='demo'))
    return dict(version=demo['version'], server_time=now, last_success=now - 7200 if demo['scenario'] == 'sync_error' else now, last_attempt=now, sync_error='network_error' if demo['scenario'] == 'sync_error' else None, course_count=3, items=rows, sync_running=False, demo=True, scenario=demo['scenario'])
