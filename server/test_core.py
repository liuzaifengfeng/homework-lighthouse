import time
from classification import classify, remaining_seconds
from store import Store


def test_user_rules_and_precedence():
    assert classify('未交', '剩余2小时30分钟', 100)['deadline_at'] == 9100
    assert classify('未交', '', 100)['category'] == 'expired'
    assert classify('待批阅', '剩余9小时', 100)['category'] == 'completed'
    assert classify('已完成', '', 100)['category'] == 'completed'
    assert classify('未开始', '', 100)['category'] == 'upcoming'
    assert classify('陌生状态', '', 100)['category'] == 'unknown'
    assert classify('未交', '页面暂时不可用', 100)['category'] == 'unknown'
    assert classify('未交', '剩余0分钟', 100)['category'] == 'expired'
    assert remaining_seconds('剩余1天2小时3分钟4秒') == 93784


def test_failure_and_missing_record_do_not_mean_submitted(tmp_path):
    db = Store(tmp_path / 'test.db')
    row = dict(id='c:k:w', course_id='c', class_id='k', course_name='课程', category='active', deadline_at=int(time.time()) + 600, observed_at=int(time.time()))
    courses = [dict(course_id='c', class_id='k', success=True)]
    db.update([row], courses)
    db.failure('network_error')
    assert db.snapshot()['items'][0]['category'] == 'active'
    assert db.snapshot()['sync_error'] == 'network_error'
    db.update([], courses)
    assert db.snapshot()['items'][0]['category'] == 'active'
    assert db.snapshot()['items'][0]['stale_reason'] == 'missing_from_list'


def test_submitted_update_removes_active_state(tmp_path):
    db = Store(tmp_path / 'test.db')
    row = dict(id='c:k:w', course_id='c', class_id='k', course_name='课程', category='active', deadline_at=int(time.time()) + 600)
    courses = [dict(course_id='c', class_id='k', success=True)]
    db.update([row], courses)
    db.update([{**row, 'category':'completed', 'deadline_at':None}], courses)
    assert not any(x['category'] == 'active' for x in db.snapshot()['items'])
