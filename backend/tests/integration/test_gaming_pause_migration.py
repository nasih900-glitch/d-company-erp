"""Legacy minute migration and refusal to discard precise new pause history."""

from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database, _run_alembic, _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0068_converts_legacy_duration_without_inventing_pause_time():
    with _disposable_database('erp_gaming_pause_0068') as url:
        result=_run_alembic(url,'upgrade','0036')
        assert result.returncode==0,result.stdout+result.stderr
        dsn=url.replace('postgresql+psycopg://','postgresql://',1)
        with psycopg.connect(dsn) as conn:
            ids=_seed_0036_cafe_scope(conn)
            conn.execute('DELETE FROM orders WHERE id=%s',(ids['order_two'],))
            conn.commit()
        result=_run_alembic(url,'upgrade','0067')
        assert result.returncode==0,result.stdout+result.stderr
        station,game=uuid4(),uuid4()
        now=datetime.now(UTC)
        with psycopg.connect(dsn) as conn:
            conn.execute("INSERT INTO stations(id,company_id,branch_id,code,name,type,rate_per_hour_minor,is_active,tax_rate,sac_code,rate_includes_tax) VALUES(%s,%s,%s,'PAUSE-MIG','Pause migration','vr',60000,true,0,'999692',true)",(station,ids['company'],ids['branch']))
            conn.execute("INSERT INTO gaming_sessions(id,company_id,station_id,shift_id,opened_by,start_at,paused_minutes,rate_per_hour_minor,status,billing_mode,extra_controllers) VALUES(%s,%s,%s,%s,%s,%s,3,60000,'paused','hourly',0)",(game,ids['company'],station,ids['shift'],ids['user'],now))
            conn.commit()
        result=_run_alembic(url,'upgrade','0068')
        assert result.returncode==0,result.stdout+result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute('SELECT paused_minutes,paused_duration_ms,paused_at,pause_version,last_pause_transition_at FROM gaming_sessions WHERE id=%s',(game,)).fetchone()==(3,180_000,None,0,None)
        result=_run_alembic(url,'downgrade','0067')
        assert result.returncode==0,result.stdout+result.stderr
        result=_run_alembic(url,'upgrade','0068')
        assert result.returncode==0,result.stdout+result.stderr
        with psycopg.connect(dsn) as conn:
            conn.execute('UPDATE gaming_sessions SET paused_at=%s,pause_version=1,last_pause_transition_at=%s WHERE id=%s',(now,now,game))
            conn.commit()
        result=_run_alembic(url,'downgrade','0067')
        assert result.returncode!=0
        assert 'preserving precise gaming pause history' in result.stdout+result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute('SELECT version_num FROM alembic_version').fetchone()==('0068',)
            assert conn.execute('SELECT paused_duration_ms,pause_version FROM gaming_sessions WHERE id=%s',(game,)).fetchone()==(180_000,1)
