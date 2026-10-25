export type PaymentStatus = 'CREATED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type RoutingStrategy = 'ROUND_ROBIN' | 'HEALTH_AWARE' | 'LATENCY_AWARE'

export interface Payment {
  paymentId: string
  merchantId: string
  amountMinor: number
  currency: string
  status: PaymentStatus
  processor: string | null
  processingTimeMs: number | null
  routingStrategy: RoutingStrategy
  createdAt: string
}

export interface PaymentAttempt {
  id: number
  processorRequestId: string
  status: string
  failureReason: string | null
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  processor: { name: string } | null
}

export interface Processor {
  id: number
  name: string
  enabled: boolean
  baseLatencyMs: number
  recentLatencyMs: number
  recentSuccessRate: number
  circuitBreakerState: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  consecutiveFailures: number
  failureProbability: number
  timeoutProbability: number
}

export interface Metrics {
  totalPayments: number
  successfulPayments: number
  failedPayments: number
  unknownPayments: number
}

export interface RoutingAnalytics {
  strategy: RoutingStrategy
  attemptCount: number
  successfulAttemptCount: number
  averageLatencyMs: number | null
}

export interface BenchmarkStrategyResult {
  totalPayments: number
  successfulPayments: number
  failedPayments: number
  unknownPayments: number
  paymentSuccessRatePct: number
  processorAttempts: number
  attemptOutcomes: Record<string, number>
  successfulProcessorAttempts: number
  attemptSuccessRatePct: number
  averageAttemptLatencyMs: number
  p95AttemptLatencyMs: number
  throughputPaymentsPerSecond: number
  processorSelectionDistribution: Record<string, number>
  processorCircuitStateAtEndOfEachSeedRun: Record<string, number>
  measuredPaymentCallTimeMs: number
}

export interface RoutingBenchmark {
  generatedAt: string
  methodology: Record<string, string | number | number[]>
  recoveryCheck: {
    expiredOpenCircuitSelectedForProbe: boolean
    oneProbeReservation: boolean
    successfulProbeClosedCircuit: boolean
    cooldownSeconds: number
  }
  scenarios: Array<{
    name: string
    processorAConfiguredFailureProbability: number
    processorAConfiguredTimeoutProbability: number
    processorBAndCConfiguredFailureProbability: number
    processorBAndCConfiguredTimeoutProbability: number
    results: Record<'ROUND_ROBIN' | 'HEALTH_AWARE', BenchmarkStrategyResult>
  }>
}

export interface SimulationResult {
  requestedCount: number
  completedCount: number
  elapsedMs: number
  routingStrategy: RoutingStrategy
  paymentStatusCounts: Partial<Record<PaymentStatus, number>>
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    const message = await response.text()
    throw new Error(message || `Request failed (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const api = {
  metrics: () => request<Metrics>('/metrics'),
  payments: () => request<Payment[]>('/payments'),
  payment: (id: string) => request<Payment>(`/payments/${id.replace(/^pay_/, '')}`),
  attempts: (id: string) => request<PaymentAttempt[]>(`/payments/${id.replace(/^pay_/, '')}/attempts`),
  processors: () => request<Processor[]>('/processors'),
  routingAnalytics: () => request<RoutingAnalytics[]>('/analytics/routing'),
  runSimulation: (payload: {
    count: number
    routingStrategy: RoutingStrategy
    processorId?: number
    failureProbability?: number
    timeoutProbability?: number
  }) => request<SimulationResult>('/simulations/run', { method: 'POST', body: JSON.stringify(payload) }),
  reconcile: (id: string) => request<Payment>(`/payments/${id.replace(/^pay_/, '')}/reconcile`, {
    method: 'POST',
    body: JSON.stringify({}),
  }),
  routingBenchmark: async (): Promise<RoutingBenchmark> => {
    const response = await fetch('/routing-benchmark.json')
    if (!response.ok) throw new Error(`Benchmark results unavailable (${response.status})`)
    return response.json() as Promise<RoutingBenchmark>
  },
}
