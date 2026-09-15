'use client'

import { FormEvent, useState } from 'react'
import { ArrowLeft, LoaderCircle, ShieldCheck, TicketCheck } from 'lucide-react'
import { useRouter } from 'next/navigation'
import api from '@/lib/api'
import type { CheckInResult } from '@/types'

export default function CheckInPage() {
  const router = useRouter()
  const [ticketNo, setTicketNo] = useState('')
  const [sessionId, setSessionId] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<CheckInResult | null>(null)
  const [error, setError] = useState('')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    setResult(null)
    const normalizedTicketNo = ticketNo.trim()
    const normalizedSessionId = Number(sessionId)
    if (!normalizedTicketNo || !Number.isInteger(normalizedSessionId) || normalizedSessionId <= 0) {
      setError('请输入有效的电子票号和场次 ID')
      return
    }

    setSubmitting(true)
    try {
      const response = await api.checkInTicket(normalizedTicketNo, normalizedSessionId)
      if (response.code !== 200 || !response.data) {
        setError(response.message || '核销失败')
        return
      }
      setResult(response.data)
    } catch (requestError: any) {
      setError(requestError?.response?.data?.message || '核销请求失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="border-b border-gray-200 bg-white">
        <div className="mx-auto flex h-[60px] max-w-[760px] items-center gap-3 px-4">
          <button title="返回" onClick={() => router.back()} className="p-2 text-gray-500 hover:text-primary">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <ShieldCheck className="h-5 w-5 text-primary" />
          <h1 className="text-lg font-medium">工作人员核销</h1>
        </div>
      </header>

      <main className="mx-auto max-w-[760px] px-4 py-10">
        <form onSubmit={submit} className="border border-gray-200 bg-white p-6 shadow-sm">
          <div className="grid gap-5">
            <label className="grid gap-2 text-sm font-medium text-gray-700">
              电子票号
              <input
                value={ticketNo}
                onChange={(event) => setTicketNo(event.target.value)}
                className="h-11 border border-gray-300 px-3 font-mono font-normal outline-none focus:border-primary"
                placeholder="ET..."
                autoComplete="off"
              />
            </label>
            <label className="grid gap-2 text-sm font-medium text-gray-700">
              场次 ID
              <input
                value={sessionId}
                onChange={(event) => setSessionId(event.target.value)}
                className="h-11 border border-gray-300 px-3 font-normal outline-none focus:border-primary"
                inputMode="numeric"
                placeholder="请输入当前入口场次 ID"
              />
            </label>
            <button
              type="submit"
              disabled={submitting}
              className="flex h-11 items-center justify-center gap-2 bg-primary px-4 font-medium text-white hover:bg-red-600 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <TicketCheck className="h-4 w-4" />}
              {submitting ? '核销中' : '确认核销'}
            </button>
          </div>
        </form>

        {error && <div className="mt-5 border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

        {result && (
          <section className="mt-5 border border-gray-200 bg-white p-6">
            <div className="flex items-center gap-3">
              <TicketCheck className="h-6 w-6 text-emerald-600" />
              <div>
                <div className="font-medium text-gray-900">{result.firstCheckIn ? '首次核销成功' : '该票已经核销'}</div>
                <div className="mt-1 text-sm text-gray-500">{result.usedAt}</div>
              </div>
            </div>
            <dl className="mt-5 grid grid-cols-[88px_1fr] gap-y-2 border-t border-gray-100 pt-4 text-sm">
              <dt className="text-gray-400">活动</dt><dd>{result.activityName}</dd>
              <dt className="text-gray-400">场馆</dt><dd>{result.venueName} · {result.hallName}</dd>
              <dt className="text-gray-400">时间</dt><dd>{result.showTime}</dd>
              <dt className="text-gray-400">座位</dt><dd>{result.seatLabel}</dd>
              <dt className="text-gray-400">电子票号</dt><dd className="break-all font-mono">{result.ticketNo}</dd>
            </dl>
          </section>
        )}
      </main>
    </div>
  )
}
