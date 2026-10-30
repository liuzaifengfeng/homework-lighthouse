import json
import sqlite3
import time
from pathlib import Path


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS assignments(id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, payload TEXT NOT NULL);
            ''')

    def connect(self):
        return sqlite3.connect(self.path, timeout=20)

    def meta(self, key, default=None):
        with self.connect() as db:
            row = db.execute('SELECT payload FROM meta WHERE key=?', (key,)).fetchone()
            return json.loads(row[0]) if row else default

    @staticmethod
    def put(db, key, value):
        db.execute('INSERT OR REPLACE INTO meta VALUES (?,?)', (key, json.dumps(value, ensure_ascii=False)))

    def failure(self, code):
        with self.connect() as db:
            self.put(db, 'last_attempt', int(time.time()))
            self.put(db, 'sync_error', code)

    def update(self, assignments, courses):
        with self.connect() as db:
            for row in assignments:
                old = db.execute('SELECT payload FROM assignments WHERE id=?', (row['id'],)).fetchone()
                if old:
                    old = json.loads(old[0])
                    # Minute-rounded countdowns fluctuate. Do not move a stable deadline on every poll.
                    if row['category'] == old['category'] == 'active' and abs(row['deadline_at'] - old.get('deadline_at', 0)) <= 90:
                        row['deadline_at'] = old['deadline_at']
                db.execute('INSERT OR REPLACE INTO assignments VALUES (?,?)', (row['id'], json.dumps(row, ensure_ascii=False)))
            # Missing records are retained; disappearing from a list never proves completion.
            observed_ids = {r['id'] for r in assignments}
            successful = {(c['course_id'], c['class_id']) for c in courses if c['success']}
            for record_id, encoded in db.execute('SELECT id,payload FROM assignments').fetchall():
                if record_id not in observed_ids:
                    row = json.loads(encoded)
                    row['stale_reason'] = 'missing_from_list' if (row['course_id'], row['class_id']) in successful else 'course_sync_failed'
                    db.execute('UPDATE assignments SET payload=? WHERE id=?', (json.dumps(row, ensure_ascii=False), record_id))
            self.put(db, 'version', self.meta('version', 0) + 1)
            self.put(db, 'last_attempt', int(time.time()))
            self.put(db, 'last_success', int(time.time()))
            self.put(db, 'courses', courses)
            self.put(db, 'sync_error', 'partial_sync' if any(not c['success'] or c.get('pagination_incomplete') for c in courses) else None)

    def snapshot(self):
        with self.connect() as db:
            items = [json.loads(x[0]) for x in db.execute('SELECT payload FROM assignments')]
        now = int(time.time())
        for row in items:
            if row['category'] == 'active' and row.get('deadline_at', now + 1) <= now:
                row['category'] = 'expired'
        items.sort(key=lambda row: (row['category'] != 'active', row.get('deadline_at') or 2**40, row['course_name']))
        return dict(version=self.meta('version', 0), server_time=now, last_success=self.meta('last_success'), last_attempt=self.meta('last_attempt'), sync_error=self.meta('sync_error'), course_count=len(self.meta('courses', [])), items=items)
