'use client'

import { Suspense, useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { ArrowLeft, CalendarDays, MapPin, Ticket as TicketIcon } from 'lucide-react'
import { toast } from 'sonner'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { TicketItem } from '@/types'

function TicketsContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const orderNo = searchParams.get('orderNo') || undefined
  const [tickets, setTickets] = useState<TicketItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.getTickets(orderNo)
      .then((res) => setTickets(res.data || []))
      .catch(() => toast.error('获取电子票失败'))
      .finally(() => setLoading(false))
  }, [orderNo])

  if (loading) return <Loading />

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="bg-white border-b border-gray-200">
        <div className="max-w-[960px] mx-auto h-[60px] px-4 flex items-center gap-4">
          <button title="返回订单" onClick={() => router.push('/orders')} className="p-2 text-gray-500 hover:text-primary">
            <ArrowLeft className="w-5 h-5" />
          </button>
          <TicketIcon className="w-5 h-5 text-primary" />
          <h1 className="text-lg font-medium">我的电子票</h1>
        </div>
      </div>

      <main className="max-w-[960px] mx-auto px-4 py-8">
        {tickets.length === 0 ? (
          <div className="py-20 text-center text-gray-400">暂无电子票</div>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            {tickets.map((ticket) => (
              <article key={ticket.ticketNo} className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                <div className="border-b border-dashed border-gray-200 px-5 py-4 flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <div className="font-medium text-gray-800 truncate">{ticket.activityName}</div>
                    <div className="text-xs text-gray-400 mt-1 font-mono break-all">{ticket.ticketNo}</div>
                  </div>
                  <span className="flex-shrink-0 text-sm font-medium text-green-600">{ticket.statusDesc}</span>
                </div>
                <div className="px-5 py-4 space-y-3 text-sm">
                  <div className="flex items-start gap-2 text-gray-600">
                    <MapPin className="w-4 h-4 mt-0.5 flex-shrink-0 text-gray-400" />
                    <span>{ticket.venueName} · {ticket.hallName}</span>
                  </div>
                  <div className="flex items-start gap-2 text-gray-600">
                    <CalendarDays className="w-4 h-4 mt-0.5 flex-shrink-0 text-gray-400" />
                    <span>{ticket.showTime}</span>
                  </div>
                  <div className="flex items-center justify-between pt-2">
                    <span className="text-gray-400">座位</span>
                    <span className="font-medium text-gray-800">{ticket.seatLabel}</span>
                  </div>
                </div>
              </article>
            ))}
          </div>
        )}
      </main>
    </div>
  )
}

export default function TicketsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <TicketsContent />
    </Suspense>
  )
}
