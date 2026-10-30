"""User-selected rules. Never confuse a failed read with an expired assignment."""
import re
import time


DONE = {'已完成', '已提交', '待批阅', '已批阅'}
PENDING = {'未交', '未提交', '待提交', '待完成', '待做', '退回重做', '待重做'}


def remaining_seconds(text):
    text = text.strip()
    if not text or '剩余' not in text:
        return None
    seconds = 0
    found = False
    for expression, scale in ((r'(\d+)天', 86400), (r'(\d+)小时', 3600), (r'(\d+)分钟', 60), (r'(\d+)秒', 1)):
        match = re.search(expression, text)
        if match:
            seconds += int(match.group(1)) * scale
            found = True
    return seconds if found else None


def classify(raw_status, time_text, observed_at=None):
    observed_at = time.time() if observed_at is None else observed_at
    seconds = remaining_seconds(time_text)
    if raw_status in DONE:
        category = 'completed'
    elif raw_status == '未开始':
        category = 'upcoming'
    elif raw_status in PENDING:
        # No time on a successfully parsed pending row is expired, per user decision.
        category = 'active' if seconds is not None and seconds > 0 else ('unknown' if time_text.strip() and seconds is None else 'expired')
    else:
        category = 'unknown'
    return {
        'category': category,
        'deadline_at': int(observed_at + seconds) if category == 'active' else None,
        'classification_basis': 'user_remaining_time_rule_v1',
    }
