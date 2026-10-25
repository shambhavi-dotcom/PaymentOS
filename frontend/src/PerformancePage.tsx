import { Activity, BarChart3, Clock3 } from 'lucide-react'
import type { ReactNode } from 'react'
import {
  Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import type { RoutingAnalytics, RoutingBenchmark, BenchmarkStrategyResult } from './api'

function displayStatus(status: string) {
  return status.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function MetricCard({ label, value, detail, icon: Icon, accent }: {
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

function ChartPanel({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return <section className="panel p-5"><div className="mb-4"><h3 className="panel-title">{title}</h3><p className="panel-subtitle">{subtitle}</p></div><div className="h-64">{children}</div></section>
}

export default function PerformancePage({
  analytics, benchmark, benchmarkError,
}: {
  analytics: RoutingAnalytics[]
  benchmark: RoutingBenchmark | null
  benchmarkError: string
}) {
  const chartData = analytics.map((row) => ({
    strategy: displayStatus(row.strategy),
    successRate: row.attemptCount > 0 ? Number(((row.successfulAttemptCount / row.attemptCount) * 100).toFixed(1)) : 0,
    latency: row.averageLatencyMs == null ? 0 : Number(row.averageLatencyMs.toFixed(1)),
  }))
  const attemptCount = analytics.reduce((sum, row) => sum + row.attemptCount, 0)
  const weightedLatency = attemptCount > 0
    ? analytics.reduce((sum, row) => sum + (row.averageLatencyMs ?? 0) * row.attemptCount, 0) / attemptCount
    : null

  return (
    <div className="space-y-5">
      <div><p className="eyebrow">Measured routing outcomes</p><h2 className="section-title">Performance analytics</h2><p className="mt-1 text-sm text-slate-500">Historical simulator observations and controlled benchmark runs are reported separately.</p></div>
      <ControlledBenchmark benchmark={benchmark} error={benchmarkError} />
      <div>
        <p className="eyebrow">Historical simulator observations</p>
        <p className="mt-1 text-sm text-slate-500">Uncontrolled persisted samples; different strategies may have different sample sizes and configurations. These aggregates do not establish a winner.</p>
      </div>
      {analytics.length === 0 ? <section className="panel"><div className="flex min-h-56 flex-col items-center justify-center px-5 text-center"><div className="mb-3 rounded-2xl bg-slate-100 p-3 text-slate-500"><BarChart3 size={22} /></div><h3 className="font-semibold text-slate-800">No routing measurements yet</h3><p className="mt-1 max-w-sm text-sm text-slate-500">Run simulations with round-robin and health-aware routing to populate this comparison with measured attempts.</p></div></section> : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <MetricCard label="Strategies observed" value={`${analytics.length}`} detail="with attempts" icon={BarChart3} accent="bg-indigo-50 text-indigo-600" />
            <MetricCard label="Attempts measured" value={`${attemptCount}`} detail="persisted attempts" icon={Activity} accent="bg-blue-50 text-blue-600" />
            <MetricCard label="Average latency" value={weightedLatency == null ? '—' : `${weightedLatency.toFixed(1)} ms`} detail="weighted by attempts" icon={Clock3} accent="bg-amber-50 text-amber-600" />
          </div>
          <div className="grid gap-5 xl:grid-cols-2">
            <ChartPanel title="Average attempt latency" subtitle="Milliseconds recorded by processor attempts">
              <ResponsiveContainer width="100%" height="100%"><BarChart data={chartData} margin={{ top: 8, right: 10, bottom: 4, left: 0 }}><CartesianGrid stroke="#edf0f5" vertical={false} /><XAxis dataKey="strategy" tickLine={false} axisLine={false} tick={{ fill: '#64748b', fontSize: 11 }} /><YAxis tickLine={false} axisLine={false} tick={{ fill: '#94a3b8', fontSize: 11 }} /><Tooltip formatter={(value) => [`${value} ms`, 'Average latency']} /><Bar dataKey="latency" fill="#6366f1" radius={[5, 5, 0, 0]} barSize={40}>{chartData.map((item) => <Cell key={item.strategy} fill={item.strategy === 'Round Robin' ? '#818cf8' : '#4f46e5'} />)}</Bar></BarChart></ResponsiveContainer>
            </ChartPanel>
            <ChartPanel title="Successful attempt rate" subtitle="Success outcomes divided by observed attempts">
              <ResponsiveContainer width="100%" height="100%"><BarChart data={chartData} margin={{ top: 8, right: 10, bottom: 4, left: 0 }}><CartesianGrid stroke="#edf0f5" vertical={false} /><XAxis dataKey="strategy" tickLine={false} axisLine={false} tick={{ fill: '#64748b', fontSize: 11 }} /><YAxis domain={[0, 100]} tickLine={false} axisLine={false} tick={{ fill: '#94a3b8', fontSize: 11 }} unit="%" /><Tooltip formatter={(value) => [`${value}%`, 'Successful attempts']} /><Bar dataKey="successRate" fill="#10b981" radius={[5, 5, 0, 0]} barSize={40} /></BarChart></ResponsiveContainer>
            </ChartPanel>
          </div>
          <section className="panel overflow-hidden">
            <div className="panel-heading"><div><h3 className="panel-title">Strategy results</h3><p className="panel-subtitle">Actual aggregates from payment_attempts and payments</p></div></div>
            <div className="overflow-x-auto"><table className="data-table"><thead><tr><th>Strategy</th><th>Attempts</th><th>Successful</th><th>Observed success</th><th>Avg. latency</th></tr></thead><tbody>{analytics.map((row) => <tr key={row.strategy}><td className="font-semibold text-slate-800">{displayStatus(row.strategy)}</td><td>{row.attemptCount}</td><td>{row.successfulAttemptCount}</td><td>{row.attemptCount ? `${((row.successfulAttemptCount / row.attemptCount) * 100).toFixed(1)}%` : '—'}</td><td>{row.averageLatencyMs == null ? '—' : `${row.averageLatencyMs.toFixed(1)} ms`}</td></tr>)}</tbody></table></div>
          </section>
        </>
      )}
    </div>
  )
}

const comparedStrategies = ['ROUND_ROBIN', 'HEALTH_AWARE'] as const

function ControlledBenchmark({ benchmark, error }: { benchmark: RoutingBenchmark | null; error: string }) {
  if (error) {
    return <section className="panel px-4 py-3 text-sm text-rose-700" role="alert">Controlled benchmark results could not be loaded: {error}</section>
  }
  if (!benchmark) {
    return <section className="panel px-4 py-4 text-sm text-slate-500">Loading controlled benchmark results…</section>
  }
  if (benchmark.scenarios.length === 0) {
    return <section className="panel px-4 py-4">
      <h3 className="panel-title">Controlled strategy comparison</h3>
      <p className="panel-subtitle">No benchmark results are published yet. Run the documented benchmark script to populate this view with measured results.</p>
    </section>
  }

  return (
    <section className="space-y-3" aria-labelledby="controlled-benchmark-title">
      <div className="panel-heading rounded-t-xl border border-b-0 border-slate-200 bg-white">
        <div>
          <p className="eyebrow">Paired seeded benchmark</p>
          <h3 id="controlled-benchmark-title" className="panel-title mt-1">Controlled strategy comparison</h3>
          <p className="panel-subtitle">Generated {new Date(benchmark.generatedAt).toLocaleString()} · {String(benchmark.methodology.paymentsPerStrategyPerScenario)} payments per strategy and scenario</p>
        </div>
      </div>
      {benchmark.scenarios.map((scenario) => (
        <div className="panel overflow-hidden" key={scenario.name}>
          <div className="panel-heading">
            <div>
              <h4 className="panel-title">{displayStatus(scenario.name)}</h4>
              <p className="panel-subtitle">
                Processor A configured failure: {(scenario.processorAConfiguredFailureProbability * 100).toFixed(0)}%;
                {' '}timeouts: {(scenario.processorAConfiguredTimeoutProbability * 100).toFixed(0)}%.
                {' '}Processors B/C configured failure/timeout: {(scenario.processorBAndCConfiguredFailureProbability * 100).toFixed(0)}% / {(scenario.processorBAndCConfiguredTimeoutProbability * 100).toFixed(0)}%.
              </p>
            </div>
            <span className="whitespace-nowrap text-[10px] font-medium text-slate-500">
              seeds: {Array.isArray(benchmark.methodology.seeds) ? benchmark.methodology.seeds.join(', ') : '—'}
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="data-table benchmark-table">
              <thead><tr>
                <th>Strategy</th><th>Payment outcomes</th><th>Payment success</th>
                <th>Attempts</th><th>Attempt success</th><th>Avg / P95 attempt</th>
                <th>Payments/s</th><th>Processor attempt distribution</th>
              </tr></thead>
              <tbody>{comparedStrategies.map((strategy) => {
                const result = scenario.results[strategy]
                return <BenchmarkRow key={strategy} strategy={strategy} result={result} />
              })}</tbody>
            </table>
          </div>
        </div>
      ))}
      <div className="panel flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-xs text-slate-600">
        <span>
          Recovery check: cooldown expired → HALF_OPEN {benchmark.recoveryCheck.expiredOpenCircuitSelectedForProbe ? 'verified' : 'not verified'};
          {' '}single probe {benchmark.recoveryCheck.oneProbeReservation ? 'verified' : 'not verified'};
          {' '}successful probe closes circuit {benchmark.recoveryCheck.successfulProbeClosedCircuit ? 'verified' : 'not verified'}.
        </span>
        <span className="font-medium text-slate-500">In-process simulator measurements; not network performance.</span>
      </div>
    </section>
  )
}

function BenchmarkRow({ strategy, result }: { strategy: typeof comparedStrategies[number]; result: BenchmarkStrategyResult }) {
  return (
    <tr>
      <td className="font-semibold text-slate-800">{displayStatus(strategy)}</td>
      <td className="whitespace-nowrap">
        {result.totalPayments} total
        <span className="mt-0.5 block text-[10px] text-slate-500">S {result.successfulPayments} · F {result.failedPayments} · U {result.unknownPayments}</span>
      </td>
      <td className="numeric-cell">{result.paymentSuccessRatePct.toFixed(2)}%</td>
      <td className="numeric-cell">{result.processorAttempts}</td>
      <td className="numeric-cell">{result.attemptSuccessRatePct.toFixed(2)}%</td>
      <td className="numeric-cell whitespace-nowrap">{result.averageAttemptLatencyMs.toFixed(1)} / {result.p95AttemptLatencyMs} ms</td>
      <td className="numeric-cell">{result.throughputPaymentsPerSecond.toFixed(2)}</td>
      <td><span className="block max-w-56 truncate" title={Object.entries(result.processorSelectionDistribution).map(([processor, count]) => `${processor}: ${count}`).join(', ')}>
        {Object.entries(result.processorSelectionDistribution).map(([processor, count]) => `${processor.replace('Benchmark ', '')}: ${count}`).join(' · ') || '—'}
      </span></td>
    </tr>
  )
}
