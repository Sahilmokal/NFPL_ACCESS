import { useEffect, useMemo, useState } from 'react';

const api = async (path, options = {}) => {
  const response = await fetch(`/api${path}`, {
    credentials: 'same-origin',
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  });
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(body?.message || `Request failed (${response.status})`);
  return body;
};

const navItems = [
  { id: 'overview', label: 'Overview', icon: 'grid' },
  { id: 'queue', label: 'My work queue', icon: 'inbox' },
  { id: 'jobs', label: 'All shipments', icon: 'box' },
  { id: 'approvals', label: 'Approvals', icon: 'check' },
  { id: 'access', label: 'My access', icon: 'shield' },
  { id: 'explorer', label: 'Access explorer', icon: 'search' },
  { id: 'admin', label: 'Access administration', icon: 'shield' },
];

const stages = ['CONFIRMED', 'PREPARED', 'CHECKED', 'FILED', 'REOPENED', 'CANCELLED'];
const branches = { 1: 'Mumbai', 2: 'Delhi', 3: 'Chennai', 4: 'Kolkata', 5: 'Bengaluru' };
const clients = {
  1: 'Aarav Textiles', 2: 'Bharat Pharma', 3: 'Coastal Spices', 4: 'Delta Auto Parts',
  5: 'Eastern Tea Co', 6: 'Fabrica Garments', 7: 'Gujarat Chemicals', 8: 'Himal Handicrafts',
  9: 'Indus Electronics', 10: 'Jaipur Gems',
};

function Icon({ name, size = 18 }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true };
  const paths = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></>,
    inbox: <><path d="M4 4h16l1 11h-6l-2 3h-2l-2-3H3L4 4Z" /><path d="M4 8h16" /></>,
    box: <><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="M3 8v9l9 5 9-5V8M12 13v9" /></>,
    check: <><path d="M20 7 10 17l-5-5" /><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h10" /></>,
    shield: <><path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z" /><path d="m9 12 2 2 4-4" /></>,
    search: <><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 5 5" /><path d="M8 11h6M11 8v6" /></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    close: <><path d="m18 6-12 12M6 6l12 12" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    arrow: <><path d="M5 12h14M13 6l6 6-6 6" /></>,
    logout: <><path d="M10 17l5-5-5-5M15 12H3" /><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6" /></>,
  };
  return <svg {...common}>{paths[name] || paths.grid}</svg>;
}

function Login({ onLogin }) {
  const [username, setUsername] = useState('asha');
  const [password, setPassword] = useState('nfpl-demo');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const user = await api('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
      onLogin(user);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-shell">
      <section className="login-brand">
        <div className="brand-lockup"><span className="brand-mark">N</span><span>NFPL <b>ACCESS</b></span></div>
        <div className="login-copy">
          <span className="eyebrow">CUSTOMS OPERATIONS · INDIA</span>
          <h1>Every shipment.<br /><em>Every decision.</em><br />Accountable.</h1>
          <p>One clear view of the work, the people who can move it, and the reasoning behind every decision.</p>
        </div>
        <div className="login-foot"><span className="online-dot" /> Access Control Center <span>·</span> Secure workspace</div>
      </section>
      <section className="login-panel">
        <div className="login-form-wrap">
          <div className="mobile-brand brand-lockup"><span className="brand-mark">N</span><span>NFPL <b>ACCESS</b></span></div>
          <div className="login-heading">
            <span className="eyebrow">WELCOME BACK</span>
            <h2>Sign in to your workspace</h2>
            <p>Use your NFPL account to continue.</p>
          </div>
          <form onSubmit={submit} className="login-form">
            <label htmlFor="username">Username</label>
            <input id="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            <label htmlFor="password">Password</label>
            <input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            {error && <div className="inline-error" role="alert">{error}</div>}
            <button className="button primary login-button" disabled={busy}>{busy ? 'Signing in…' : <>Sign in <Icon name="arrow" /></>}</button>
          </form>
          <div className="demo-hint"><Icon name="shield" /><span><b>Demo access</b><br />Try <code>asha</code> or <code>dev</code> with password <code>nfpl-demo</code>.</span></div>
          <p className="login-legal">Demo credentials are for local evaluation only. Configure a unique password before any shared deployment.</p>
        </div>
      </section>
    </main>
  );
}

function StatCard({ label, value, note, icon, tone }) {
  return <article className="stat-card"><div className={`stat-icon ${tone}`}><Icon name={icon} /></div><div className="stat-label">{label}</div><div className="stat-value">{value}</div><div className="stat-note">{note}</div></article>;
}

function StagePill({ stage }) {
  return <span className={`stage-pill stage-${(stage || 'unknown').toLowerCase()}`}>{(stage || 'Unknown').replaceAll('_', ' ')}</span>;
}

function JobTable({ items, onSelect, emptyText = 'Nothing here yet.' }) {
  if (!items.length) return <div className="empty-state"><span className="empty-mark"><Icon name="inbox" size={23} /></span><b>All clear</b><p>{emptyText}</p></div>;
  return <div className="table-scroll"><table className="jobs-table"><thead><tr><th>SHIPMENT</th><th>CLIENT / BRANCH</th><th>WORKFLOW</th><th>NEXT STEP</th><th>UPDATED</th><th /></tr></thead><tbody>
    {items.map((job) => <tr key={job.id} onClick={() => onSelect(job.id)} tabIndex="0" onKeyDown={(e) => e.key === 'Enter' && onSelect(job.id)}>
      <td><span className="job-ref">{job.jobNo}</span><span className="job-title">{job.title || 'Untitled shipment'}</span></td>
      <td><span className="cell-primary">{clients[job.clientId] || `Client #${job.clientId}`}</span><span className="cell-secondary">{branches[job.branchId] || `Branch #${job.branchId}`} · {job.direction} · {job.mode}</span></td>
      <td><StagePill stage={job.stage} /></td>
      <td>{job.nextAction ? <span className="next-action">{job.nextAction.replaceAll('_', ' ')}</span> : <span className="muted">—</span>}</td>
      <td className="date-cell">{job.updatedAt ? new Date(job.updatedAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' }) : '—'}</td>
      <td><button className="icon-button row-open" aria-label={`Open ${job.jobNo}`}><Icon name="chevron" /></button></td>
    </tr>)}
  </tbody></table></div>;
}

function App() {
  const [user, setUser] = useState(null);
  const [starting, setStarting] = useState(true);
  const [page, setPage] = useState('overview');
  const [filter, setFilter] = useState('all');
  const [queue, setQueue] = useState({ count: 0, items: [] });
  const [jobs, setJobs] = useState([]);
  const [approvals, setApprovals] = useState([]);
  const [access, setAccess] = useState(null);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState('');
  const [notice, setNotice] = useState('');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState(null);
  const [jobDetails, setJobDetails] = useState(null);
  const [history, setHistory] = useState(null);
  const [historyError, setHistoryError] = useState('');
  const [actionJob, setActionJob] = useState(null);
  const [actionReason, setActionReason] = useState('');
  const [actionApprovalId, setActionApprovalId] = useState('');
  const [busyAction, setBusyAction] = useState(false);
  const [compare, setCompare] = useState({ userA: '2', userB: '17', action: 'CHECK', jobId: '4' });
  const [comparison, setComparison] = useState(null);
  const [compareError, setCompareError] = useState('');
  const [directory, setDirectory] = useState([]);
  const [adminRoles, setAdminRoles] = useState([]);
  const [adminGrants, setAdminGrants] = useState([]);
  const [selectedAdminUser, setSelectedAdminUser] = useState('');
  const [grantForm, setGrantForm] = useState({ roleId: '', clientId: '', branchId: '', direction: '', mode: '', reason: '' });
  const [revokeReason, setRevokeReason] = useState('');

  useEffect(() => {
    api('/auth/me').then(setUser).catch(() => setUser(null)).finally(() => setStarting(false));
  }, []);

  async function refresh() {
    if (!user) return;
    setLoading(true);
    setPageError('');
    try {
      if (page === 'overview' || page === 'queue') {
        const [q, visible] = await Promise.all([
          api(`/users/${user.id}/queue?limit=50&filter=${filter}`),
          page === 'overview' ? api('/jobs?limit=50') : Promise.resolve(jobs),
        ]);
        setQueue(q);
        if (page === 'overview') setJobs(visible);
      } else if (page === 'jobs') {
        setJobs(await api('/jobs?limit=100'));
      } else if (page === 'approvals') {
        setApprovals(await api('/approvals'));
      } else if (page === 'access') {
        setAccess(await api(`/users/${user.id}/access`));
      } else if (page === 'admin') {
        const [people, roles] = await Promise.all([api('/users/directory'), api('/roles')]);
        setDirectory(people);
        setAdminRoles(roles);
        if (!selectedAdminUser && people.length) {
          const targetId = String((people.find((person) => person.id !== user.id) || people[0]).id);
          setSelectedAdminUser(targetId);
        }
      }
    } catch (e) {
      setPageError(e.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, [user, page, filter]);

  useEffect(() => {
    if (page !== 'admin' || !selectedAdminUser) return;
    api(`/grants?userId=${selectedAdminUser}`).then(setAdminGrants).catch((e) => setPageError(e.message));
  }, [page, selectedAdminUser]);

  useEffect(() => {
    if (selectedId == null) return;
    setJobDetails(null);
    setHistory(null);
    setHistoryError('');
    api(`/jobs/${selectedId}`).then(setJobDetails).catch((e) => setHistoryError(e.message));
    api(`/jobs/${selectedId}/history`).then(setHistory).catch((e) => setHistoryError(e.message));
  }, [selectedId]);

  const filteredItems = useMemo(() => {
    const source = page === 'jobs' ? jobs : queue.items;
    const needle = query.trim().toLowerCase();
    return needle ? source.filter((job) => `${job.jobNo} ${job.title || ''} ${clients[job.clientId] || ''}`.toLowerCase().includes(needle)) : source;
  }, [page, jobs, queue.items, query]);

  async function signOut() {
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* Expired sessions are already signed out. */ }
    setUser(null);
    setPage('overview');
  }

  async function runAction(event) {
    event.preventDefault();
    if (!actionJob) return;
    setBusyAction(true);
    setNotice('');
    setPageError('');
    try {
      const result = await api(`/jobs/${actionJob.id}/actions/${actionJob.nextAction}`, {
        method: 'POST',
        body: JSON.stringify({
          reason: actionReason || null,
          approvalId: actionApprovalId ? Number(actionApprovalId) : null,
        }),
      });
      setNotice(result.approvalId
        ? `${result.message || 'Second-person approval requested.'} Approval #${result.approvalId}.`
        : result.message || `${actionJob.nextAction} completed.`);
      setActionJob(null);
      setActionReason('');
      setActionApprovalId('');
      setSelectedId(null);
      await refresh();
    } catch (e) {
      setPageError(e.message);
    } finally {
      setBusyAction(false);
    }
  }

  async function decideApproval(id, decision) {
    setPageError('');
    try {
      await api(`/approvals/${id}/${decision}`, { method: 'POST', body: JSON.stringify({}) });
      setNotice(`Approval #${id} ${decision === 'approve' ? 'approved' : 'rejected'}.`);
      await refresh();
    } catch (e) { setPageError(e.message); }
  }

  async function runCompare(event) {
    event.preventDefault();
    setCompareError('');
    setComparison(null);
    try {
      const params = new URLSearchParams(compare);
      setComparison(await api(`/access/compare?${params}`));
    } catch (e) { setCompareError(e.message); }
  }

  async function createGrant(event) {
    event.preventDefault();
    if (!selectedAdminUser || !grantForm.roleId) return;
    setPageError('');
    try {
      const result = await api('/grants', {
        method: 'POST',
        body: JSON.stringify({
          userId: Number(selectedAdminUser),
          roleId: Number(grantForm.roleId),
          clientId: grantForm.clientId ? Number(grantForm.clientId) : null,
          branchId: grantForm.branchId ? Number(grantForm.branchId) : null,
          direction: grantForm.direction || null,
          mode: grantForm.mode || null,
          reason: grantForm.reason || null,
        }),
      });
      setNotice(result.message || 'Access request submitted.');
      setGrantForm({ ...grantForm, reason: '' });
      await refresh();
    } catch (e) { setPageError(e.message); }
  }

  async function revokeGrant(id) {
    setPageError('');
    try {
      const result = await api(`/grants/${id}/revoke`, {
        method: 'POST',
        body: JSON.stringify({ reason: revokeReason || 'Revoked through the access administration workspace' }),
      });
      setNotice(`Grant #${id} revoked. ${result.jobsReleasedToPool} jobs returned to the pool.`);
      setRevokeReason('');
      setAdminGrants(await api(`/grants?userId=${selectedAdminUser}`));
    } catch (e) { setPageError(e.message); }
  }

  if (starting) return <div className="starting-screen"><span className="loader" /> Loading workspace…</div>;
  if (!user) return <Login onLogin={setUser} />;

  const activeJob = [...(queue.items || []), ...jobs].find((item) => item.id === selectedId);
  const drawerActions = jobDetails ? [
    activeJob?.nextAction,
    ...(jobDetails.stage === 'FILED' ? ['CANCEL_FILED', 'REOPEN'] : []),
    ...(jobDetails.stage === 'CANCELLED' ? ['REOPEN'] : []),
  ].filter((action, index, list) => action && list.indexOf(action) === index) : [];
  const visibleJobs = filteredItems;
  const pageTitles = {
    overview: ['Operations overview', 'Your day, at a glance.'],
    queue: ['My work queue', 'The shipments waiting for your next step.'],
    jobs: ['All shipments', 'Browse the work available within your access scope.'],
    approvals: ['Approval inbox', 'Sensitive actions need a second set of eyes.'],
    access: ['My access', 'Your active roles and what they allow.'],
    explorer: ['Access explorer', 'See why one person can act and another cannot.'],
    admin: ['Access administration', 'Manage scoped grants without widening your authority.'],
  };

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup sidebar-brand"><span className="brand-mark">N</span><span>NFPL <b>ACCESS</b></span></div>
        <div className="workspace-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          {navItems.map((item) => <button key={item.id} className={`nav-item ${page === item.id ? 'active' : ''}`} onClick={() => { setPage(item.id); setQuery(''); }}>
            <Icon name={item.icon} /><span>{item.label}</span>{item.id === 'queue' && queue.count > 0 && <span className="nav-count">{queue.count}</span>}
          </button>)}
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-card"><div className="secure-icon"><Icon name="shield" size={17} /></div><b>Access is enforced</b><p>Every action is checked against your live permissions.</p><span><i /> All systems operational</span></div>
          <div className="profile-card">
            <div className="avatar">{user.fullName.split(' ').map((part) => part[0]).slice(0, 2).join('')}</div>
            <div className="profile-info"><b>{user.fullName}</b><span>{user.userType === 'CLIENT' ? 'Client workspace' : user.status === 'ABSENT' ? 'Currently absent' : 'Operations team'}</span></div>
            <button className="icon-button signout" onClick={signOut} title="Sign out" aria-label="Sign out"><Icon name="logout" /></button>
          </div>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumb"><span>NFPL</span><Icon name="chevron" size={14} /><b>{pageTitles[page][0]}</b></div>
          <div className="topbar-right"><span className="today-label">{new Date().toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}</span><span className="header-divider" /><span className="top-avatar">{user.fullName.split(' ').map((part) => part[0]).slice(0, 2).join('')}</span><span className="top-user">{user.fullName}</span></div>
        </header>

        <div className="content-area">
          <div className="page-heading"><div><span className="eyebrow">ACCESS CONTROL CENTER</span><h1>{pageTitles[page][0]}</h1><p>{pageTitles[page][1]}</p></div>
            <button className="button outline refresh-button" onClick={refresh} disabled={loading}><span className={loading ? 'spinning' : ''}>↻</span> Refresh</button>
          </div>
          {notice && <div className="notice-banner"><Icon name="check" size={17} />{notice}<button onClick={() => setNotice('')} aria-label="Dismiss"><Icon name="close" size={15} /></button></div>}
          {pageError && <div className="error-banner" role="alert"><Icon name="shield" size={17} />{pageError}<button onClick={() => setPageError('')} aria-label="Dismiss"><Icon name="close" size={15} /></button></div>}

          {page === 'overview' && <section className="dashboard">
            <div className="welcome-strip"><div className="welcome-mark"><span className="online-dot" /></div><div><span className="eyebrow">YOUR WORKSPACE IS READY</span><h2>Good day, {user.fullName.split(' ')[0]}.</h2><p>Here’s what’s moving across your operation today.</p></div><div className="welcome-art" aria-hidden="true"><div className="art-ring ring-one" /><div className="art-ring ring-two" /><div className="art-shape">N</div></div></div>
            <div className="stats-grid">
              <StatCard label="Awaiting your action" value={queue.count.toLocaleString()} note="Based on your live access" icon="inbox" tone="blue" />
              <StatCard label="Shipments in view" value={jobs.length.toLocaleString()} note="Visible within your scope" icon="box" tone="green" />
              <StatCard label="Active role" value={user.status === 'ABSENT' ? 'Absent' : user.userType === 'CLIENT' ? 'Client' : 'Verified'} note={user.userType === 'CLIENT' ? 'Client-side access' : 'Identity confirmed'} icon="shield" tone="violet" />
            </div>
            <section className="panel">
              <div className="panel-heading"><div><span className="eyebrow">NEXT UP</span><h2>Work that needs you</h2></div><button className="text-button" onClick={() => setPage('queue')}>Open work queue <Icon name="arrow" size={15} /></button></div>
              <JobTable items={queue.items.slice(0, 5)} onSelect={setSelectedId} emptyText="There are no jobs in your queue right now." />
            </section>
            <div className="bottom-grid">
              <section className="panel stage-panel"><div className="panel-heading"><div><span className="eyebrow">PIPELINE</span><h2>Shipment stages</h2></div><Icon name="clock" /></div><div className="stage-list">
                {stages.slice(0, 4).map((stage) => <div className="stage-row" key={stage}><span className={`stage-dot stage-dot-${stage.toLowerCase()}`} /><span>{stage.charAt(0) + stage.slice(1).toLowerCase()}</span><div className="stage-track"><i style={{ width: `${Math.min(100, jobs.filter((job) => job.stage === stage).length * 9 + 12)}%` }} /></div><b>{jobs.filter((job) => job.stage === stage).length}</b></div>)}
              </div></section>
              <section className="audit-card"><div className="audit-icon"><Icon name="shield" size={20} /></div><span className="eyebrow">BUILT FOR TRUST</span><h2>Every decision<br />has a reason.</h2><p>Access is evaluated at the moment of action. The service records the result and the reasoning trail.</p><button className="audit-link" onClick={() => setPage('access')}>Review my access <Icon name="arrow" size={15} /></button></section>
            </div>
          </section>}

          {(page === 'queue' || page === 'jobs') && <section className="panel list-panel">
            <div className="list-toolbar">
              {page === 'queue' ? <div className="filter-tabs" role="tablist">{['all', 'mine', 'pool'].map((value) => <button key={value} className={filter === value ? 'selected' : ''} onClick={() => setFilter(value)}>{value === 'all' ? 'All work' : value === 'mine' ? 'Assigned to me' : 'Unassigned pool'}</button>)}</div> : <div className="list-count">{jobs.length} shipments shown</div>}
              <label className="search-field"><Icon name="search" size={16} /><input placeholder="Search shipment…" value={query} onChange={(e) => setQuery(e.target.value)} /></label>
            </div>
            <JobTable items={visibleJobs} onSelect={setSelectedId} emptyText={page === 'queue' ? 'No shipments match this queue filter.' : 'No shipments are visible to your account.'} />
            <div className="table-footer"><span>Showing {visibleJobs.length} of {page === 'queue' ? queue.count : jobs.length} {page === 'queue' ? 'actionable shipments' : 'shipments'}</span><span className="scope-note"><Icon name="shield" size={14} /> Filtered by your permissions</span></div>
          </section>}

          {page === 'approvals' && <section className="panel approval-panel"><div className="panel-heading"><div><span className="eyebrow">SECOND-PERSON REVIEW</span><h2>Requests awaiting your decision</h2></div><span className="approval-total">{approvals.length} pending</span></div>
            {approvals.length ? <div className="approval-list">{approvals.map((item) => <article className="approval-item" key={item.id}><div className="approval-stamp"><Icon name={item.kind === 'GRANT' ? 'shield' : 'check'} /></div><div className="approval-copy"><span className="eyebrow">{item.kind === 'GRANT' ? 'ACCESS REQUEST' : 'SENSITIVE JOB ACTION'} · #{item.id}</span><h3>{item.action || 'Sensitive role grant'}{item.job_id ? ` · Job #${item.job_id}` : ''}</h3><p>{item.reason || 'No reason provided'} · Requested by {item.requested_by_name}</p></div><div className="approval-actions"><button className="button outline" onClick={() => decideApproval(item.id, 'reject')}>Reject</button><button className="button primary" onClick={() => decideApproval(item.id, 'approve')}>Approve</button></div></article>)}</div> : <div className="empty-state"><span className="empty-mark"><Icon name="check" size={23} /></span><b>No pending approvals</b><p>Any approval requests that require your scope will appear here.</p></div>}
          </section>}

          {page === 'access' && <section className="panel access-panel"><div className="panel-heading"><div><span className="eyebrow">AUTHORITY SNAPSHOT</span><h2>{access?.user || 'Your permissions'}</h2></div>{access?.status && <span className={`status-chip ${access.status.toLowerCase()}`}>{access.status}</span>}</div><p className="access-intro">Each grant is scoped to the clients, branches, directions and modes shown below. The API re-evaluates these rules for every action.</p>
            {access?.lines?.map((line, index) => <div className="grant-line" key={`${index}-${line}`}><div className="grant-index">{String(index + 1).padStart(2, '0')}</div><div className="grant-text">{line}</div><span className="grant-live"><i /> LIVE</span></div>)}
            {access?.lines?.length === 0 && <div className="empty-state"><b>No active grants</b><p>Contact an access administrator if you need a role.</p></div>}
            <div className="access-footnote"><Icon name="shield" size={16} /><span><b>Separation of duties is always enforced.</b> Having a role does not let you approve your own work.</span></div>
          </section>}

          {page === 'admin' && <section className="admin-layout">
            <form className="panel grant-form" onSubmit={createGrant}>
              <div className="panel-heading"><div><span className="eyebrow">SCOPED AUTHORITY</span><h2>Grant a role</h2></div><div className="explorer-icon"><Icon name="shield" /></div></div>
              <p className="access-intro">Grants take effect immediately unless the role requires second-person approval. Your own scope is enforced by the API.</p>
              <div className="admin-form-grid">
                <label>Person<select required value={selectedAdminUser} onChange={(e) => setSelectedAdminUser(e.target.value)}><option value="" disabled>Select a person</option>{directory.filter((person) => person.id !== user.id).map((person) => <option key={person.id} value={person.id}>{person.full_name} · #{person.id} · {person.status}</option>)}</select></label>
                <label>Role<select required value={grantForm.roleId} onChange={(e) => setGrantForm({ ...grantForm, roleId: e.target.value })}><option value="">Select a role</option>{adminRoles.map((role) => <option key={role.id} value={role.id}>{role.name}{role.is_sensitive ? ' · approval required' : ''}</option>)}</select></label>
                <label>Client scope<select value={grantForm.clientId} onChange={(e) => setGrantForm({ ...grantForm, clientId: e.target.value })}><option value="">All clients</option>{Object.entries(clients).map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>
                <label>Branch scope<select value={grantForm.branchId} onChange={(e) => setGrantForm({ ...grantForm, branchId: e.target.value })}><option value="">All branches</option>{Object.entries(branches).map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>
                <label>Direction<select value={grantForm.direction} onChange={(e) => setGrantForm({ ...grantForm, direction: e.target.value })}><option value="">All directions</option><option>IMPORT</option><option>EXPORT</option></select></label>
                <label>Mode<select value={grantForm.mode} onChange={(e) => setGrantForm({ ...grantForm, mode: e.target.value })}><option value="">All modes</option><option>AIR</option><option>FCL</option><option>LCL</option></select></label>
                <label className="wide-field">Reason<input value={grantForm.reason} onChange={(e) => setGrantForm({ ...grantForm, reason: e.target.value })} placeholder="Why is this access needed?" /></label>
              </div>
              <div className="form-footer"><span><Icon name="shield" size={14} /> Scope limits and self-grant protection stay server-enforced.</span><button className="button primary" disabled={!selectedAdminUser || !grantForm.roleId}>Submit grant <Icon name="arrow" size={15} /></button></div>
            </form>
            <section className="panel grant-list-panel">
              <div className="panel-heading"><div><span className="eyebrow">GRANT HISTORY</span><h2>{directory.find((person) => String(person.id) === selectedAdminUser)?.full_name || 'Select a person'}</h2></div><span className="approval-total">{adminGrants.length} grants</span></div>
              <div className="revoke-reason"><label htmlFor="revoke-reason">Revocation reason</label><input id="revoke-reason" value={revokeReason} onChange={(e) => setRevokeReason(e.target.value)} placeholder="Optional audit note" /></div>
              {adminGrants.length ? <div className="admin-grants">{adminGrants.map((grant) => {
                const revoked = Boolean(grant.revoked_at);
                const scope = [
                  grant.client_id ? clients[grant.client_id] || `Client #${grant.client_id}` : 'All clients',
                  grant.branch_id ? branches[grant.branch_id] || `Branch #${grant.branch_id}` : 'All branches',
                  grant.direction || 'All directions',
                  grant.mode || 'All modes',
                ].join(' · ');
                return <article className="admin-grant-row" key={grant.id}><div className="grant-role-icon"><Icon name="shield" size={16} /></div><div className="admin-grant-copy"><b>{grant.role_name} <span>#{grant.id}</span></b><p>{scope}</p><small>{revoked ? `Revoked ${grant.revoked_at}` : grant.valid_to ? `Valid until ${grant.valid_to} UTC` : 'No expiry'}{grant.reason ? ` · ${grant.reason}` : ''}</small></div>{revoked ? <span className="revoked-badge">REVOKED</span> : <button className="button revoke-button" onClick={() => revokeGrant(grant.id)}>Revoke</button>}</article>;
              })}</div> : <div className="empty-state"><span className="empty-mark"><Icon name="shield" size={22} /></span><b>No visible grants</b><p>This person has no grants in your administration scope.</p></div>}
            </section>
          </section>}

          {page === 'explorer' && <section className="explorer-layout">
            <form className="panel explorer-form" onSubmit={runCompare}><div className="panel-heading"><div><span className="eyebrow">DECISION SIMULATOR</span><h2>Compare access</h2></div><div className="explorer-icon"><Icon name="search" /></div></div><p className="access-intro">Compare two people against the same job and workflow action.</p>
              <div className="form-grid"><label>First user ID<input value={compare.userA} onChange={(e) => setCompare({ ...compare, userA: e.target.value })} inputMode="numeric" required /></label><label>Second user ID<input value={compare.userB} onChange={(e) => setCompare({ ...compare, userB: e.target.value })} inputMode="numeric" required /></label><label>Shipment ID<input value={compare.jobId} onChange={(e) => setCompare({ ...compare, jobId: e.target.value })} inputMode="numeric" required /></label><label>Action<select value={compare.action} onChange={(e) => setCompare({ ...compare, action: e.target.value })}><option>PREPARE</option><option>CHECK</option><option>FILE</option><option>CANCEL</option><option>REOPEN</option></select></label></div>
              {compareError && <div className="inline-error" role="alert">{compareError}</div>}<button className="button primary compare-button">Compare decisions <Icon name="arrow" /></button><div className="demo-ids">Demo: Ravi <code>2</code> vs Farhan <code>17</code> · job <code>4</code> · CHECK</div>
            </form>
            {comparison && <section className="panel comparison-panel"><span className="eyebrow">EVALUATION RESULT</span><h2>{comparison.job}</h2><p className="comparison-summary">{comparison.summary}</p>{['a', 'b'].map((key) => { const result = comparison[key]; return <article className="decision-result" key={key}><div className="decision-head"><b>{result.user}</b><span className={`decision-badge ${result.allowed ? 'allowed' : 'denied'}`}>{result.allowed ? 'ALLOWED' : 'DENIED'}</span></div><p>{result.message}</p><div className="trace-list">{result.trace?.map((step, index) => <div key={index} className={step.passed ? 'trace-step passed' : 'trace-step blocked'}><span>{step.passed ? '✓' : '×'}</span>{step.detail}</div>)}</div></article>; })}</section>}
          </section>}
        </div>
      </main>

      {selectedId != null && <div className="drawer-backdrop" onMouseDown={(e) => e.target === e.currentTarget && setSelectedId(null)}>
        <aside className="job-drawer" aria-label="Shipment details">
          <div className="drawer-header"><div><span className="eyebrow">SHIPMENT DETAIL</span><h2>{jobDetails?.jobNo || 'Loading…'}</h2></div><button className="icon-button" onClick={() => setSelectedId(null)} aria-label="Close details"><Icon name="close" /></button></div>
          {jobDetails ? <><div className="drawer-status"><StagePill stage={jobDetails.stage} /><span className="muted">Updated {new Date(jobDetails.updatedAt).toLocaleString('en-IN')}</span></div>
            <div className="detail-title">{jobDetails.title || 'Untitled shipment'}</div>
            <div className="detail-grid"><div><span>CLIENT</span><b>{clients[jobDetails.clientId] || `Client #${jobDetails.clientId}`}</b></div><div><span>BRANCH</span><b>{branches[jobDetails.branchId] || `Branch #${jobDetails.branchId}`}</b></div><div><span>DIRECTION</span><b>{jobDetails.direction}</b></div><div><span>MODE</span><b>{jobDetails.mode}</b></div><div><span>OWNER</span><b>{jobDetails.ownerId ? `User #${jobDetails.ownerId}` : 'Unassigned'}</b></div><div><span>VERSION</span><b>{jobDetails.version}</b></div></div>
            {drawerActions.length > 0 && <div className="drawer-actions">{drawerActions.map((action) => <button key={action} className="button primary drawer-action" onClick={() => { setActionJob({ ...jobDetails, nextAction: action }); setActionReason(''); setActionApprovalId(''); }}>{action.replaceAll('_', ' ')} <Icon name="arrow" /></button>)}</div>}
            <div className="drawer-section-title"><span className="eyebrow">AUDIT TRAIL</span><span>{history?.events?.length || 0} events</span></div>
            {historyError ? <div className="history-error">{historyError}</div> : <div className="timeline">{(history?.events || []).map((event, index) => <div className="timeline-item" key={event.id || index}><span className="timeline-dot" /><div><b>{event.eventType || event.event_type || 'Event'}</b><p>{event.reason || event.actorName || event.actor_name || 'Recorded by access control'}</p><span>{event.createdAt || event.created_at || ''}</span></div></div>)}{history && !history.events?.length && <p className="muted">No history events returned.</p>}</div>}
          </> : !historyError && <div className="drawer-loading"><span className="loader" /> Loading shipment details…</div>}
          {historyError && !jobDetails && <div className="history-error">{historyError}</div>}
        </aside>
      </div>}

      {actionJob && <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && setActionJob(null)}><form className="action-modal" onSubmit={runAction}>
        <div className="modal-top"><div className="modal-icon"><Icon name="check" /></div><button type="button" className="icon-button" onClick={() => setActionJob(null)} aria-label="Close"><Icon name="close" /></button></div>
        <span className="eyebrow">WORKFLOW ACTION</span><h2>{actionJob.nextAction.replaceAll('_', ' ')}</h2><p>Shipment <b>{actionJob.jobNo}</b>. Your access will be checked against the current workflow stage.</p>
        <label className="reason-label">Reason <span>Required for sensitive actions</span><textarea rows="3" value={actionReason} onChange={(e) => setActionReason(e.target.value)} placeholder="Add context for the audit trail…" /></label>
        {['CANCEL_FILED', 'REOPEN'].includes(actionJob.nextAction) && <label className="reason-label approval-id-label">Approval ID <span>Enter the approved request ID to complete this action</span><input inputMode="numeric" value={actionApprovalId} onChange={(e) => setActionApprovalId(e.target.value)} placeholder="Optional until approval is granted" /></label>}
        <div className="modal-actions"><button type="button" className="button outline" onClick={() => setActionJob(null)}>Cancel</button><button className="button primary" disabled={busyAction}>{busyAction ? 'Submitting…' : 'Confirm action'} <Icon name="arrow" /></button></div>
      </form></div>}
    </div>
  );
}

export default App;
