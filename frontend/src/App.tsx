import { Suspense, lazy, useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity, AlertCircle, ArrowDownRight, ArrowUpRight, ArrowUpRightFromSquare,
  BarChart3, Check, ChevronDown, CircleHelp, Clock3, CreditCard, FlaskConical,
  LayoutDashboard, LoaderCircle, Menu, PanelLeftClose, PanelLeftOpen, RefreshCw,
  Search, ShieldCheck, SlidersHorizontal, X, Zap,
} from 'lucide-react'
import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import {
  api, type Metrics, type Payment, type PaymentAttempt, type PaymentStatus,
  type Processor, type RoutingAnalytics, type RoutingBenchmark, type RoutingStrategy, type SimulationResult,
} from './api'

type Page = 'Overview' | 'Transactions' | 'Processor health' | 'Simulation lab' | 'Performance'
const PerformancePage = lazy(() => import('./PerformancePage'))

const navigation: { label: Page; icon: typeof LayoutDashboard }[] = [
  { label: 'Overview', icon: LayoutDashboard },
  { label: 'Transactions', icon: CreditCard },
  { label: 'Processor health', icon: ShieldCheck },
  { label: 'Simulation lab', icon: FlaskConical },
  { label: 'Performance', icon: BarChart3 },
]

const statusStyles: Record<PaymentStatus, string> = {
  CREATED: 'bg-slate-100 text-slate-600',
  PROCESSING: 'bg-blue-50 text-blue-700',
  SUCCEEDED: 'bg-emerald-50 text-emerald-700',
  FAILED: 'bg-rose-50 text-rose-700',
  UNKNOWN: 'bg-amber-50 text-amber-700',
}

function displayStatus(status: string) {
  return status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

function formatAmount(amountMinor: number, currency: string) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: 2 })
    .format(amountMinor / 100)
}

function StatusBadge({ status }: { status: PaymentStatus | Processor['circuitBreakerState'] }) {
  const tone = status in statusStyles
    ? statusStyles[status as PaymentStatus]
    : status === 'CLOSED'
      ? 'bg-emerald-50 text-emerald-700'
      : status === 'OPEN'
        ? 'bg-rose-50 text-rose-700'
        : 'bg-amber-50 text-amber-700'
  const marker = status === 'SUCCEEDED' || status === 'CLOSED'
    ? 'bg-emerald-500'
    : status === 'FAILED' || status === 'OPEN'
      ? 'bg-rose-500'
      : status === 'UNKNOWN' || status === 'HALF_OPEN'
        ? 'bg-amber-500'
        : status === 'PROCESSING'
          ? 'bg-blue-500'
          : 'bg-slate-400'
  return <span className={`status-badge ${tone}`}><i className={marker} aria-hidden="true" />{displayStatus(status)}</span>
}

function MetricCard({
  label, value, detail, icon: Icon, accent,
}: {
  label: string
  value: string
  detail: string
  icon: typeof Activity
  accent: string
}) {
  return (
    <article className="metric-card">
      <div className={`metric-icon ${accent}`}><Icon size={19} strokeWidth={2} /></div>
      <p className="metric-label">{label}</p>
      <div className="mt-3 flex items-end justify-between gap-2">
        <p className="metric-value">{value}</p>
        <span className="pb-1 text-xs text-slate-500">{detail}</span>
      </div>
    </article>
  )
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex min-h-56 flex-col items-center justify-center px-5 text-center">
      <div className="mb-3 rounded-2xl bg-slate-100 p-3 text-slate-500"><CircleHelp size={22} /></div>
      <h3 className="font-semibold text-slate-800">{title}</h3>
      <p className="mt-1 max-w-sm text-sm text-slate-500">{description}</p>
    </div>
  )
}

function LoadingState({ label = 'Loading live data…' }: { label?: string }) {
  return <div className="flex min-h-48 items-center justify-center gap-2 text-sm text-slate-500"><LoaderCircle className="animate-spin" size={18} />{label}</div>
}

function App() {
  const [page, setPage] = useState<Page>('Overview')
  const [metrics, setMetrics] = useState<Metrics | null>(null)
  const [payments, setPayments] = useState<Payment[]>([])
  const [processors, setProcessors] = useState<Processor[]>([])
  const [analytics, setAnalytics] = useState<RoutingAnalytics[]>([])
  const [benchmark, setBenchmark] = useState<RoutingBenchmark | null>(null)
  const [benchmarkError, setBenchmarkError] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [selectedPayment, setSelectedPayment] = useState<Payment | null>(null)
  const [attempts, setAttempts] = useState<PaymentAttempt[]>([])
  const [detailLoading, setDetailLoading] = useState(false)
  const [reconciling, setReconciling] = useState(false)
  const [notice, setNotice] = useState('')
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextMetrics, nextPayments, nextProcessors, nextAnalytics] = await Promise.all([
        api.metrics(), api.payments(), api.processors(), api.routingAnalytics(),
      ])
      setMetrics(nextMetrics)
      setPayments(nextPayments)
      setProcessors(nextProcessors)
      setAnalytics(nextAnalytics)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load dashboard data.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    api.routingBenchmark()
      .then(setBenchmark)
      .catch((cause: unknown) => setBenchmarkError(cause instanceof Error ? cause.message : 'Could not load controlled benchmark results.'))
  }, [])

  const showNotice = (message: string) => {
    setNotice(message)
    window.setTimeout(() => setNotice(''), 4500)
  }

  const openPayment = async (payment: Payment) => {
    setSelectedPayment(payment)
    setDetailLoading(true)
    try {
      const [detail, paymentAttempts] = await Promise.all([
        api.payment(payment.paymentId),
        api.attempts(payment.paymentId),
      ])
      setSelectedPayment(detail)
      setAttempts(paymentAttempts)
    } catch (cause) {
      showNotice(cause instanceof Error ? cause.message : 'Could not load payment details.')
      setAttempts([])
    } finally {
      setDetailLoading(false)
    }
  }

  const filteredPayments = useMemo(() => payments.filter((payment) => {
    const matchesStatus = filter === 'ALL' || payment.status === filter
    const query = search.trim().toLowerCase()
    const matchesSearch = !query || payment.paymentId.toLowerCase().includes(query)
      || payment.merchantId.toLowerCase().includes(query)
      || payment.processor?.toLowerCase().includes(query)
    return matchesStatus && matchesSearch
  }), [filter, payments, search])

  const navigate = (label: Page) => {
    setPage(label)
    setMobileNavOpen(false)
  }

  const onReconcile = async () => {
    if (!selectedPayment) return
    setReconciling(true)
    try {
      const updated = await api.reconcile(selectedPayment.paymentId)
      setSelectedPayment(updated)
      await refresh()
      showNotice(`Processor request reconciled: ${displayStatus(updated.status)}.`)
      const nextAttempts = await api.attempts(updated.paymentId)
      setAttempts(nextAttempts)
    } catch (cause) {
      showNotice(cause instanceof Error ? cause.message : 'Reconciliation failed.')
    } finally {
      setReconciling(false)
    }
  }

  const successfulRate = metrics && metrics.totalPayments > 0
    ? (metrics.successfulPayments / metrics.totalPayments) * 100
    : null

  return (
    <div className="min-h-screen bg-[#f5f7fb] text-slate-900">
      <aside id="primary-navigation" className={`sidebar ${mobileNavOpen ? 'sidebar-open' : ''} ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
        <div className="flex items-center gap-3 px-5 py-6">
          <div className="brand-mark"><Zap size={19} fill="currentColor" /></div>
          <div className="sidebar-brand-copy">
            <p className="text-[15px] font-bold tracking-tight text-white">PaymentOS</p>
            <p className="mt-0.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-400">Operations center</p>
          </div>
          <button className="ml-auto rounded-lg p-2 text-slate-400 hover:bg-white/10 lg:hidden" onClick={() => setMobileNavOpen(false)} aria-label="Close menu"><X size={18} /></button>
        </div>

        <div className="px-4 pt-7">
          <p className="sidebar-overline">Workspace</p>
          <nav className="mt-3 space-y-1">
            {navigation.map(({ label, icon: Icon }) => (
              <button key={label} title={sidebarCollapsed ? label : undefined} aria-current={page === label ? 'page' : undefined} onClick={() => navigate(label)} className={`nav-item ${page === label ? 'nav-item-active' : ''}`}>
                <Icon size={17} strokeWidth={1.8} />
                <span className="sidebar-nav-label">{label}</span>
                {label === 'Transactions' && metrics && <span className="sidebar-nav-count">{metrics.totalPayments}</span>}
              </button>
            ))}
          </nav>
        </div>

        <div className="sidebar-bottom">
          <div className="sidebar-connection flex items-center gap-2 rounded-xl border border-white/10 bg-white/[0.04] px-3 py-3">
            <span className="relative flex h-2 w-2">
              <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
            </span>
            <div className="sidebar-connection-copy">
              <p className="text-xs font-semibold text-slate-200">API connection</p>
              <p className="text-[11px] text-slate-400">{error ? 'Connection issue' : loading ? 'Connecting…' : 'Connected to backend'}</p>
            </div>
          </div>
          <p className="sidebar-environment mt-4 px-1 text-[10px] text-slate-500">PAYMENTOS · DEVELOPMENT</p>
        </div>
      </aside>

      {mobileNavOpen && <button className="fixed inset-0 z-30 bg-slate-950/40 lg:hidden" onClick={() => setMobileNavOpen(false)} aria-label="Close navigation overlay" />}

      <main className={`workspace ${sidebarCollapsed ? 'workspace-collapsed' : ''}`}>
        <header className="topbar">
          <div className="flex min-w-0 items-center gap-3">
            <button className="nav-toggle desktop-nav-toggle" onClick={() => setSidebarCollapsed((collapsed) => !collapsed)} aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-controls="primary-navigation" aria-expanded={!sidebarCollapsed} title={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
              {sidebarCollapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
            </button>
            <button className="nav-toggle mobile-nav-toggle" onClick={() => setMobileNavOpen(true)} aria-label="Open menu" aria-controls="primary-navigation" aria-expanded={mobileNavOpen}>
              <Menu size={19} />
            </button>
            <div className="min-w-0">
              <div className="flex items-center gap-2 text-xs text-slate-400"><span>PaymentOS</span><span>/</span><span className="font-medium text-slate-600">{page}</span></div>
              <h1 className="mt-1 truncate text-[19px] font-semibold tracking-tight text-slate-900">{page}</h1>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className="hidden text-xs text-slate-500 sm:block">Live backend data</span>
            <button className="button-secondary !px-3 !py-2" onClick={() => void refresh()} disabled={loading} title="Refresh dashboard">
              <RefreshCw size={15} className={loading ? 'animate-spin' : ''} /><span className="hidden sm:inline">Refresh</span>
            </button>
            <div className="avatar">PO</div>
          </div>
        </header>

        <div className="content-area">
          {error && (
            <div role="alert" className="mb-5 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
              <AlertCircle className="mt-0.5 shrink-0" size={17} />
              <div className="flex-1"><p className="font-semibold">Unable to reach the PaymentOS API</p><p className="mt-0.5 break-words text-rose-700">{error}</p></div>
              <button className="font-semibold underline" onClick={() => void refresh()}>Retry</button>
            </div>
          )}

          {loading && !metrics ? <LoadingState /> : (
            <>
              {page === 'Overview' && (
                <OverviewPage
                  metrics={metrics}
                  payments={payments}
                  processors={processors}
                  rate={successfulRate}
                  onPayment={openPayment}
                  onNavigate={navigate}
                />
              )}
              {page === 'Transactions' && (
                <TransactionsPage
                  payments={filteredPayments}
                  filter={filter}
                  search={search}
                  setFilter={setFilter}
                  setSearch={setSearch}
                  onPayment={openPayment}
                />
              )}
              {page === 'Processor health' && <ProcessorHealthPage processors={processors} onRefresh={() => void refresh()} />}
              {page === 'Simulation lab' && (
                <SimulationPage
                  processors={processors}
                  onRun={async (payload) => {
                    const result = await api.runSimulation(payload)
                    await refresh()
                    return result
                  }}
                  onNotice={showNotice}
                />
              )}
              {page === 'Performance' && <Suspense fallback={<LoadingState label="Loading measured analytics…" />}><PerformancePage analytics={analytics} benchmark={benchmark} benchmarkError={benchmarkError} /></Suspense>}
            </>
          )}
        </div>
      </main>

      {notice && <div role="status" className="toast"><Check size={17} />{notice}</div>}
      {selectedPayment && (
        <PaymentDrawer
          payment={selectedPayment}
          attempts={attempts}
          loading={detailLoading}
          reconciling={reconciling}
          onClose={() => { setSelectedPayment(null); setAttempts([]) }}
          onReconcile={() => void onReconcile()}
        />
      )}
    </div>
  )
}

function OverviewPage({
  metrics, payments, processors, rate, onPayment, onNavigate,
}: {
  metrics: Metrics | null
  payments: Payment[]
  processors: Processor[]
  rate: number | null
  onPayment: (payment: Payment) => void
  onNavigate: (page: Page) => void
}) {
  const healthy = processors.filter((processor) => processor.enabled && processor.circuitBreakerState !== 'OPEN').length
  const activity = useMemo(() => {
    const days = new Map<string, { day: string; succeeded: number; failed: number; unresolved: number; inProgress: number }>()
    payments.forEach((payment) => {
      const created = new Date(payment.createdAt)
      if (Number.isNaN(created.getTime())) return
      const day = [
        created.getFullYear(),
        String(created.getMonth() + 1).padStart(2, '0'),
        String(created.getDate()).padStart(2, '0'),
      ].join('-')
      const entry = days.get(day) ?? { day, succeeded: 0, failed: 0, unresolved: 0, inProgress: 0 }
      if (payment.status === 'SUCCEEDED') entry.succeeded += 1
      else if (payment.status === 'FAILED') entry.failed += 1
      else if (payment.status === 'UNKNOWN') entry.unresolved += 1
      else entry.inProgress += 1
      days.set(day, entry)
    })
    return [...days.values()].sort((left, right) => left.day.localeCompare(right.day)).slice(-14)
  }, [payments])

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow">Operations overview</p>
          <h2 className="section-title">Payment activity</h2>
          <p className="mt-1 text-sm text-slate-500">A live view of transactions and processor health.</p>
        </div>
        <button className="button-primary" onClick={() => onNavigate('Simulation lab')}><FlaskConical size={16} />Run simulation</button>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="Total payments" value={metrics?.totalPayments.toLocaleString() ?? '—'} detail="all time" icon={CreditCard} accent="bg-indigo-50 text-indigo-600" />
        <MetricCard label="Succeeded" value={metrics?.successfulPayments.toLocaleString() ?? '—'} detail={rate === null ? 'no activity' : `${rate.toFixed(1)}% of total`} icon={Check} accent="bg-emerald-50 text-emerald-600" />
        <MetricCard label="Failed" value={metrics?.failedPayments.toLocaleString() ?? '—'} detail="terminal failures" icon={ArrowDownRight} accent="bg-rose-50 text-rose-600" />
        <MetricCard label="Unresolved" value={metrics?.unknownPayments.toLocaleString() ?? '—'} detail={`${healthy} processors available`} icon={Clock3} accent="bg-amber-50 text-amber-600" />
      </div>

      <section className="panel overflow-hidden">
        <div className="panel-heading">
          <div><h3 className="panel-title">Transaction activity</h3><p className="panel-subtitle">Daily payment outcomes from persisted records · latest 14 active days</p></div>
          <div className="activity-legend" aria-label="Chart legend">
            <span><i className="bg-indigo-500" />Succeeded</span>
            <span><i className="bg-rose-400" />Failed</span>
            <span><i className="bg-amber-400" />Unresolved</span>
            <span><i className="bg-slate-300" />In progress</span>
          </div>
        </div>
        {activity.length === 0 ? (
          <EmptyState title="No activity history available" description="Persisted payment records will appear here after the first payment is created." />
        ) : (
          <div className="activity-chart" role="img" aria-label="Daily payment counts by observed status, calculated from persisted payment records">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={activity} margin={{ top: 8, right: 12, bottom: 2, left: -18 }}>
                <CartesianGrid stroke="#edf0f5" vertical={false} />
                <XAxis
                  dataKey="day"
                  tickFormatter={(value: string) => new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(new Date(`${value}T12:00:00`))}
                  tickLine={false}
                  axisLine={false}
                  minTickGap={18}
                  tick={{ fill: '#8490a3', fontSize: 10 }}
                />
                <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fill: '#8490a3', fontSize: 10 }} />
                <Tooltip
                  labelFormatter={(value) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(`${String(value)}T12:00:00`))}
                  formatter={(value) => [value, 'Payments']}
                  contentStyle={{ border: '1px solid #e9edf4', borderRadius: 10, fontSize: 12 }}
                />
                <Bar dataKey="succeeded" name="Succeeded" stackId="outcomes" fill="#6366f1" />
                <Bar dataKey="failed" name="Failed" stackId="outcomes" fill="#fb7185" />
                <Bar dataKey="unresolved" name="Unresolved" stackId="outcomes" fill="#fbbf24" />
                <Bar dataKey="inProgress" name="In progress" stackId="outcomes" fill="#cbd5e1" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </section>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.7fr)_minmax(300px,0.9fr)]">
        <section className="panel overflow-hidden">
          <div className="panel-heading">
            <div><h3 className="panel-title">Recent transactions</h3><p className="panel-subtitle">Latest payments recorded by the backend</p></div>
            <button className="text-sm font-semibold text-indigo-600 hover:text-indigo-800" onClick={() => onNavigate('Transactions')}>View all <ArrowUpRightFromSquare className="ml-1 inline" size={13} /></button>
          </div>
          <PaymentTable payments={payments.slice(0, 7)} onPayment={onPayment} compact />
        </section>

        <section className="panel">
          <div className="panel-heading">
            <div><h3 className="panel-title">Processor health</h3><p className="panel-subtitle">Current availability and breaker state</p></div>
            <button className="icon-button" aria-label="View processor health" onClick={() => onNavigate('Processor health')}><ArrowUpRight size={17} /></button>
          </div>
          {processors.length === 0 ? <EmptyState title="No processors configured" description="Processor records will appear here when the backend has initialized them." /> : (
            <div className="space-y-2 px-4 pb-4">
              {processors.map((processor) => <ProcessorRow key={processor.id} processor={processor} />)}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function TransactionsPage({
  payments, filter, search, setFilter, setSearch, onPayment,
}: {
  payments: Payment[]
  filter: string
  search: string
  setFilter: (filter: string) => void
  setSearch: (search: string) => void
  onPayment: (payment: Payment) => void
}) {
  return (
    <div className="space-y-5">
      <div><p className="eyebrow">Payment ledger</p><h2 className="section-title">Transactions</h2><p className="mt-1 text-sm text-slate-500">Search, filter, and inspect persisted payment attempts.</p></div>
      <section className="panel overflow-hidden">
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full sm:max-w-xs"><Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={16} /><input className="form-input pl-9" placeholder="Search payment, merchant…" value={search} onChange={(event) => setSearch(event.target.value)} /></div>
          <label className="flex items-center gap-2 text-sm text-slate-500"><SlidersHorizontal size={15} /><select className="form-select min-w-36" value={filter} onChange={(event) => setFilter(event.target.value)}><option value="ALL">All statuses</option><option value="SUCCEEDED">Succeeded</option><option value="FAILED">Failed</option><option value="UNKNOWN">Unknown</option><option value="PROCESSING">Processing</option><option value="CREATED">Created</option></select><ChevronDown className="pointer-events-none -ml-8 mr-3 text-slate-400" size={14} /></label>
        </div>
        <PaymentTable payments={payments} onPayment={onPayment} />
      </section>
    </div>
  )
}

function PaymentTable({ payments, onPayment, compact = false }: { payments: Payment[]; onPayment: (payment: Payment) => void; compact?: boolean }) {
  if (payments.length === 0) return <EmptyState title="No transactions yet" description="Payments created through the API or simulation lab will show up here." />
  return (
    <div className="overflow-x-auto">
      <table className={`data-table ${compact ? 'data-table-compact' : ''}`}>
        <thead><tr><th>Payment</th><th>Amount</th><th>Status</th><th>Processor</th>{!compact && <th>Routing</th>}<th>Latency</th><th>Created</th></tr></thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.paymentId} onClick={() => onPayment(payment)} className="cursor-pointer">
              <td><span className="payment-id">{payment.paymentId}</span><span className="merchant-id" title={payment.merchantId} aria-label={`Merchant: ${payment.merchantId}`}>{payment.merchantId}</span></td>
              <td className="numeric-cell whitespace-nowrap font-medium text-slate-700">{formatAmount(payment.amountMinor, payment.currency)}</td>
              <td><StatusBadge status={payment.status} /></td>
              <td className="whitespace-nowrap text-slate-600">{payment.processor ?? '—'}</td>
              {!compact && <td><span className="text-xs text-slate-500">{displayStatus(payment.routingStrategy ?? 'HEALTH_AWARE')}</span></td>}
              <td className="numeric-cell whitespace-nowrap text-slate-600">{payment.processingTimeMs == null ? '—' : `${payment.processingTimeMs} ms`}</td>
              <td className="whitespace-nowrap text-xs text-slate-500">{payment.createdAt ? formatDate(payment.createdAt) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ProcessorRow({ processor }: { processor: Processor }) {
  const available = processor.enabled && processor.circuitBreakerState !== 'OPEN'
  return (
    <div className="flex items-center justify-between gap-3 rounded-xl border border-slate-100 px-3.5 py-3">
      <div className="flex min-w-0 items-center gap-3">
        <div className={`processor-dot ${available ? 'bg-emerald-500' : 'bg-rose-500'}`} />
        <div className="min-w-0"><p className="truncate text-sm font-semibold text-slate-800">{processor.name}</p><p className="mt-0.5 text-xs text-slate-500">{available ? 'Available' : processor.enabled ? 'Circuit open' : 'Disabled'}</p></div>
      </div>
      <div className="text-right"><p className="text-sm font-semibold text-slate-700">{processor.recentLatencyMs} <span className="text-xs font-normal text-slate-400">ms</span></p><p className="mt-0.5 text-xs text-slate-500">{(processor.recentSuccessRate * 100).toFixed(0)}% success</p></div>
    </div>
  )
}

function ProcessorHealthPage({ processors, onRefresh }: { processors: Processor[]; onRefresh: () => void }) {
  const available = processors.filter((processor) => processor.enabled && processor.circuitBreakerState !== 'OPEN').length
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><p className="eyebrow">Routing infrastructure</p><h2 className="section-title">Processor health</h2><p className="mt-1 text-sm text-slate-500">Availability, latency, and persisted circuit-breaker state.</p></div>
        <button className="button-secondary" onClick={onRefresh}><RefreshCw size={15} />Refresh status</button>
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        <MetricCard label="Configured processors" value={`${processors.length}`} detail="backend records" icon={CreditCard} accent="bg-indigo-50 text-indigo-600" />
        <MetricCard label="Available now" value={`${available}`} detail="enabled and not open" icon={Activity} accent="bg-emerald-50 text-emerald-600" />
        <MetricCard label="Circuit open" value={`${processors.filter((processor) => processor.circuitBreakerState === 'OPEN').length}`} detail="cooldown / recovery" icon={ShieldCheck} accent="bg-rose-50 text-rose-600" />
      </div>
      {processors.length === 0 ? <section className="panel"><EmptyState title="No processors configured" description="The backend did not return any processors." /></section> : (
        <div className="grid gap-3 xl:grid-cols-3">
          {processors.map((processor) => (
            <article className="panel processor-card" key={processor.id}>
              <div className="processor-card-heading">
                <div className="min-w-0">
                  <h3 className="truncate text-sm font-semibold text-slate-900">{processor.name}</h3>
                  <span className={`availability-label ${processor.enabled && processor.circuitBreakerState !== 'OPEN' ? 'availability-ready' : 'availability-excluded'}`}>
                    {processor.enabled && processor.circuitBreakerState !== 'OPEN'
                      ? 'Routable · enabled'
                      : processor.enabled ? 'Excluded · circuit open' : 'Excluded · disabled'}
                  </span>
                </div>
                <div className="text-right">
                  <span className="field-label !text-[10px] !uppercase !tracking-wider !text-slate-400">Circuit breaker</span>
                  <StatusBadge status={processor.circuitBreakerState} />
                </div>
              </div>
              <div className="processor-metrics">
                <div className="processor-metric-row"><span>Recent latency</span><strong>{processor.recentLatencyMs} ms</strong></div>
                <div className="processor-metric-row"><span>Recent success rate</span><strong>{(processor.recentSuccessRate * 100).toFixed(1)}%</strong></div>
                <div className="processor-metric-row"><span>Consecutive failures</span><strong>{processor.consecutiveFailures}</strong></div>
                <div className="processor-probabilities">
                  <div><span>Failure probability</span><strong>{(processor.failureProbability * 100).toFixed(0)}%</strong></div>
                  <div><span>Timeout probability</span><strong>{(processor.timeoutProbability * 100).toFixed(0)}%</strong></div>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}

function SimulationPage({
  processors, onRun, onNotice,
}: {
  processors: Processor[]
  onRun: (payload: { count: number; routingStrategy: RoutingStrategy; processorId?: number; failureProbability?: number; timeoutProbability?: number }) => Promise<SimulationResult>
  onNotice: (message: string) => void
}) {
  const [count, setCount] = useState(50)
  const [strategy, setStrategy] = useState<RoutingStrategy>('ROUND_ROBIN')
  const [injectFailures, setInjectFailures] = useState(false)
  const [processorId, setProcessorId] = useState('')
  const [failurePercent, setFailurePercent] = useState(20)
  const [timeoutPercent, setTimeoutPercent] = useState(5)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<SimulationResult | null>(null)

  const run = async () => {
    setRunning(true)
    try {
      const payload: Parameters<typeof onRun>[0] = { count, routingStrategy: strategy }
      if (injectFailures) {
        if (!processorId) throw new Error('Select a processor before injecting failures.')
        if (failurePercent + timeoutPercent > 100) throw new Error('Failure and timeout percentages must total 100% or less.')
        payload.processorId = Number(processorId)
        payload.failureProbability = failurePercent / 100
        payload.timeoutProbability = timeoutPercent / 100
      }
      const next = await onRun(payload)
      setResult(next)
      onNotice(`Simulation finished: ${next.completedCount} payments processed in ${next.elapsedMs} ms.`)
    } catch (cause) {
      onNotice(cause instanceof Error ? cause.message : 'Simulation request failed.')
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="space-y-5">
      <div><p className="eyebrow">Controlled traffic generation</p><h2 className="section-title">Simulation lab</h2><p className="mt-1 text-sm text-slate-500">Generate real persisted test payments and measure the selected router.</p></div>
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(320px,0.8fr)]">
        <section className="panel p-5 sm:p-6">
          <div className="mb-5 flex items-center gap-3"><div className="rounded-xl bg-indigo-50 p-2.5 text-indigo-600"><FlaskConical size={19} /></div><div><h3 className="panel-title">Run a traffic scenario</h3><p className="panel-subtitle">Every generated payment is written to backend storage.</p></div></div>
          <div className="space-y-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="field-label">Payment count <span className="font-normal text-slate-400">(1–500)</span><input className="form-input mt-2" type="number" min={1} max={500} value={count} onChange={(event) => setCount(Number(event.target.value))} /></label>
              <label className="field-label">Routing strategy<select className="form-select mt-2" value={strategy} onChange={(event) => setStrategy(event.target.value as RoutingStrategy)}><option value="ROUND_ROBIN">Round robin</option><option value="HEALTH_AWARE">Health aware</option><option value="LATENCY_AWARE">Latency aware</option></select></label>
            </div>
            <div className="rounded-xl border border-slate-200 p-4">
              <label className="flex cursor-pointer items-center gap-3">
                <input className="h-4 w-4 accent-indigo-600" type="checkbox" checked={injectFailures} onChange={(event) => setInjectFailures(event.target.checked)} />
                <span><span className="block text-sm font-semibold text-slate-800">Inject processor failures</span><span className="mt-0.5 block text-xs text-slate-500">Updates the selected processor’s real simulator probabilities.</span></span>
              </label>
              {injectFailures && (
                <div className="mt-4 grid gap-4 border-t border-slate-100 pt-4 sm:grid-cols-3">
                  <label className="field-label sm:col-span-3">Target processor<select className="form-select mt-2" value={processorId} onChange={(event) => setProcessorId(event.target.value)}><option value="">Choose processor</option>{processors.map((processor) => <option value={processor.id} key={processor.id}>{processor.name} · {processor.enabled ? 'enabled' : 'disabled'}</option>)}</select></label>
                  <label className="field-label">Pre-execution failure %<input className="form-input mt-2" type="number" min={0} max={100} value={failurePercent} onChange={(event) => setFailurePercent(Number(event.target.value))} /></label>
                  <label className="field-label">Timeout %<input className="form-input mt-2" type="number" min={0} max={100} value={timeoutPercent} onChange={(event) => setTimeoutPercent(Number(event.target.value))} /></label>
                  <div className="flex items-end pb-2 text-xs text-slate-500">Combined maximum: 100%</div>
                </div>
              )}
            </div>
            <button className="button-primary w-full justify-center sm:w-auto" disabled={running || count < 1 || count > 500} onClick={() => void run()}>{running ? <LoaderCircle className="animate-spin" size={16} /> : <Zap size={16} />}{running ? 'Processing payments…' : 'Run simulation'}</button>
          </div>
        </section>

        <section className="panel p-5 sm:p-6">
          <div className="mb-4 flex items-center justify-between"><div><h3 className="panel-title">Latest run</h3><p className="panel-subtitle">Measured from this API response</p></div><Activity className="text-indigo-500" size={19} /></div>
          {!result ? <EmptyState title="No simulation run yet" description="Run a scenario to see the payment statuses and measured elapsed time." /> : (
            <div>
              <div className="grid grid-cols-2 gap-3">
                <div className="stat-box"><p>Processed</p><strong>{result.completedCount}<span> payments</span></strong></div>
                <div className="stat-box"><p>Elapsed time</p><strong>{result.elapsedMs}<span> ms</span></strong></div>
                <div className="stat-box"><p>Routing strategy</p><strong className="!text-base">{displayStatus(result.routingStrategy)}</strong></div>
                <div className="stat-box"><p>Server-reported count</p><strong>{result.requestedCount}</strong></div>
              </div>
              <h4 className="mt-5 text-xs font-semibold uppercase tracking-wider text-slate-500">Payment outcomes</h4>
              <div className="mt-2 space-y-2">
                {Object.entries(result.paymentStatusCounts).map(([status, amount]) => (
                  <div key={status} className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2.5"><StatusBadge status={status as PaymentStatus} /><span className="text-sm font-semibold text-slate-700">{amount}</span></div>
                ))}
              </div>
            </div>
          )}
        </section>
      </div>
      <div className="flex items-start gap-2 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-xs leading-relaxed text-blue-800"><CircleHelp className="mt-0.5 shrink-0" size={15} /><span>Failure injection persists the chosen probability on that processor until changed. Timeouts remain UNKNOWN until reconciled; the backend does not retry an uncertain charge.</span></div>
    </div>
  )
}

function PaymentDrawer({
  payment, attempts, loading, reconciling, onClose, onReconcile,
}: {
  payment: Payment
  attempts: PaymentAttempt[]
  loading: boolean
  reconciling: boolean
  onClose: () => void
  onReconcile: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex justify-end bg-slate-950/35" onClick={onClose}>
      <aside className="drawer" onClick={(event) => event.stopPropagation()}>
        <div className="flex items-start justify-between border-b border-slate-100 px-5 py-5">
          <div><p className="eyebrow">Payment detail</p><h2 className="mt-1 text-lg font-semibold text-slate-900">{payment.paymentId}</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close details"><X size={18} /></button>
        </div>
        {loading ? <LoadingState label="Loading payment attempts…" /> : (
          <div className="space-y-6 overflow-y-auto p-5">
            <div className="flex items-center justify-between"><span className="text-sm text-slate-500">Current status</span><StatusBadge status={payment.status} /></div>
            <div className="grid grid-cols-2 gap-3">
              <div className="stat-box"><p>Amount</p><strong>{formatAmount(payment.amountMinor, payment.currency)}</strong></div>
              <div className="stat-box"><p>Processor</p><strong className="!text-sm">{payment.processor ?? '—'}</strong></div>
              <div className="stat-box"><p>Routing</p><strong className="!text-sm">{displayStatus(payment.routingStrategy ?? 'HEALTH_AWARE')}</strong></div>
              <div className="stat-box"><p>Latency</p><strong>{payment.processingTimeMs == null ? '—' : `${payment.processingTimeMs} ms`}</strong></div>
            </div>
            <div className="text-xs text-slate-500"><span className="font-semibold text-slate-700">Merchant:</span> {payment.merchantId}<br /><span className="mt-1 inline-block font-semibold text-slate-700">Created:</span> {payment.createdAt ? formatDate(payment.createdAt) : '—'}</div>
            {payment.status === 'UNKNOWN' && <div className="rounded-xl border border-amber-200 bg-amber-50 p-4"><p className="text-sm font-semibold text-amber-900">Processor outcome is uncertain</p><p className="mt-1 text-xs leading-relaxed text-amber-800">Ask the simulator to reconcile the original processor request. The final status is not supplied by the client.</p><button className="button-primary mt-3 !bg-amber-700 hover:!bg-amber-800" disabled={reconciling} onClick={onReconcile}>{reconciling ? <LoaderCircle className="animate-spin" size={15} /> : <RefreshCw size={15} />}Reconcile with processor</button></div>}
            <div><h3 className="text-sm font-semibold text-slate-900">Processor attempts <span className="ml-1 text-xs font-normal text-slate-400">{attempts.length}</span></h3>
              {attempts.length === 0 ? <p className="mt-3 text-sm text-slate-500">No persisted attempts for this payment.</p> : (
                <ol className="mt-3 space-y-3">
                  {attempts.map((attempt, index) => <li key={attempt.id} className="relative rounded-xl border border-slate-200 p-3.5">
                    <div className="flex items-center justify-between gap-2"><p className="text-sm font-semibold text-slate-800">Attempt {index + 1} · {attempt.processor?.name ?? 'Processor'}</p><StatusBadge status={attempt.status === 'SUCCESS' ? 'SUCCEEDED' : attempt.status === 'DECLINED' ? 'FAILED' : attempt.status === 'TIMEOUT_UNKNOWN' ? 'UNKNOWN' : 'FAILED'} /></div>
                    <p className="mt-2 break-all text-[11px] text-slate-500">Request ID: {attempt.processorRequestId}</p>
                    <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500"><span>{attempt.durationMs == null ? 'Duration unavailable' : `${attempt.durationMs} ms`}</span><span>{formatDate(attempt.startedAt)}</span></div>
                    {attempt.failureReason && <p className="mt-2 text-xs text-rose-700">{attempt.failureReason}</p>}
                  </li>)}
                </ol>
              )}
            </div>
          </div>
        )}
      </aside>
    </div>
  )
}

export default App
