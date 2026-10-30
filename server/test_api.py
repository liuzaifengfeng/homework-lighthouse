from fastapi.testclient import TestClient
import app as module
from store import Store


def test_private_api_requires_token_and_demo_does_not_change_real_data(tmp_path, monkeypatch):
    monkeypatch.setattr(module, 'TOKEN', 'integration-test-token')
    monkeypatch.setattr(module, 'store', Store(tmp_path / 'api.db'))
    monkeypatch.setattr(module, 'secrets', {})
    with TestClient(module.app) as client:
        assert client.get('/healthz').status_code == 200
        assert client.get('/api/v1/snapshot').status_code == 401
        headers = {'Authorization':'Bearer integration-test-token'}
        assert client.get('/api/v1/snapshot', headers=headers).json()['items'] == []
        for scenario, expected_active in [('urgent',3), ('submitted_one',2), ('submitted_all',0), ('expired',0)]:
            assert client.post('/api/v1/demo/scenario', headers=headers, json={'name':scenario}).status_code == 200
            data = client.get('/api/v1/demo/snapshot', headers=headers).json()
            assert data['demo']
            assert len([r for r in data['items'] if r['category'] == 'active']) == expected_active
            assert client.get('/api/v1/snapshot', headers=headers).json()['items'] == []
