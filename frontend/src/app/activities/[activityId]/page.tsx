'use client'

import { useState, useEffect } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { ArrowLeft, MapPin } from 'lucide-react'
import Loading from '@/components/Loading'
import TopIntro from '@/components/activity-detail/TopIntro'
import StagePhoto from '@/components/activity-detail/StagePhoto'
import api from '@/lib/api'
import type { ActivityDetail, VenueSessionGroup, SessionItem } from '@/types'

export default function ActivityDetailPage() {
  const router = useRouter()
  const params = useParams()
  const activityId = params.activityId as string
  const [isReady, setIsReady] = useState(false)
  const [detail, setDetail] = useState<ActivityDetail | null>(null)
  const [showSchedule, setShowSchedule] = useState(false)
  const [dates, setDates] = useState<string[]>([])
  const [activeDate, setActiveDate] = useState('')
  const [venueGroups, setVenueGroups] = useState<VenueSessionGroup[]>([])
  const [scheduleLoading, setScheduleLoading] = useState(false)

  useEffect(() => {
    if (!activityId) return
    api
      .getActivityDetail(activityId)
      .then((res) => {
        setDetail(res.data)
        setIsReady(true)
      })
      .catch(() => setIsReady(true))
  }, [activityId])

  const loadDates = () => {
    if (!activityId) return
    api.getActivityAvailableDates(activityId)
      .then((res) => {
        const dateList = res.data || []
        setDates(dateList)
        if (dateList.length > 0) {
          setActiveDate(dateList[0])
          loadSchedules(dateList[0])
        }
      })
      .catch(() => {})
  }

  const loadSchedules = (date: string) => {
    if (!activityId) return
    setScheduleLoading(true)
    api.getActivitySessionsGroupedByVenue(activityId, { showDate: date })
      .then((res) => {
        setVenueGroups(res.data || [])
      })
      .catch(() => {})
      .finally(() => setScheduleLoading(false))
  }

  const handleShowSchedule = () => {
    if (!showSchedule) {
      setShowSchedule(true)
      loadDates()
    } else {
      setShowSchedule(false)
    }
  }

  const handleDateSelect = (date: string) => {
    setActiveDate(date)
    loadSchedules(date)
  }

  const handleSelectSession = (session: SessionItem) => {
    router.push(`/seat-selection?scheduleId=${session.sessionId}&activityId=${activityId}`)
  }

  const formatDateLabel = (dateStr: string) => {
    const d = new Date(dateStr)
    const today = new Date()
    const tomorrow = new Date()
    tomorrow.setDate(today.getDate() + 1)
    const month = d.getMonth() + 1
    const day = d.getDate()
    const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    if (dateStr === today.toISOString().slice(0, 10)) return `今天 ${month}月${day}日`
    if (dateStr === tomorrow.toISOString().slice(0, 10)) return `明天 ${month}月${day}日`
    return `${weekdays[d.getDay()]} ${month}月${day}日`
  }

  if (!isReady) return <Loading />
  if (!detail) return <div className="text-center py-20 text-gray-400">未找到活动信息</div>

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="bg-white border-b border-gray-200 min-w-[1200px]">
        <div className="max-w-[1200px] mx-auto h-[60px] flex items-center gap-4">
          <ArrowLeft className="w-6 h-6 text-gray-500 cursor-pointer hover:text-primary" onClick={() => router.push('/')} />
          <h1 className="text-lg font-medium">{detail.name}</h1>
        </div>
      </div>

      <div className="max-w-[1200px] mx-auto mt-6 bg-white rounded shadow-sm">
        <TopIntro activityDetail={detail} />
        {detail.photos && <StagePhoto photos={detail.photos} photoTotal={detail.photoCount || 0} />}

        <div className="px-8 py-6 border-t border-gray-100">
          <button onClick={handleShowSchedule} className="px-8 py-3 bg-primary text-white rounded-full text-base hover:bg-red-600 transition-colors">
            {showSchedule ? '收起场次' : '查看场次'}
          </button>
        </div>

        {showSchedule && (
          <div className="px-8 pb-8">
            {dates.length > 0 && (
              <div className="flex gap-3 mb-6 border-b border-gray-100 pb-4">
                {dates.map((d) => (
                  <button key={d} onClick={() => handleDateSelect(d)} className={`px-4 py-2 rounded-lg text-sm transition-colors ${d === activeDate ? 'bg-primary text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'}`}>
                    {formatDateLabel(d)}
                  </button>
                ))}
              </div>
            )}

            {scheduleLoading ? (
              <Loading />
            ) : venueGroups.length === 0 ? (
              <div className="text-center py-12 text-gray-400">当日暂无场次</div>
            ) : (
              <div className="space-y-6">
                {venueGroups.map((group) => (
                  <div key={group.venue.id} className="border border-gray-200 rounded-lg overflow-hidden">
                    <div className="bg-gray-50 px-6 py-4 border-b border-gray-200">
                      <h3 className="font-medium text-gray-800 text-base">{group.venue.name}</h3>
                      {group.venue.address && (
                        <div className="flex items-center text-sm text-gray-500 mt-1">
                          <MapPin className="w-3.5 h-3.5 mr-1" />
                          <span>{group.venue.address}</span>
                        </div>
                      )}
                    </div>
                    <div className="divide-y divide-gray-100">
                      {group.sessions.map((session) => (
                        <div key={session.sessionId} className="flex items-center justify-between px-6 py-4 hover:bg-gray-50 transition-colors">
                          <div className="flex items-center gap-8">
                            <div className="text-center">
                              <div className="text-lg font-bold text-gray-800">{session.showTime}</div>
                              <div className="text-xs text-gray-400">{session.endTime}结束</div>
                            </div>
                            <div className="text-sm text-gray-600">
                              <div className="flex items-center gap-2">
                                <span>{session.language}</span>
                                <span className="text-gray-300">|</span>
                                <span>{session.hallName}</span>
                              </div>
                            </div>
                          </div>
                          <div className="flex items-center gap-6">
                            <div className="text-right">
                              <span className="text-xs text-primary">¥</span>
                              <span className="text-xl font-bold text-primary">{session.price}</span>
                            </div>
                            <button onClick={() => handleSelectSession(session)} className="px-6 py-2 bg-primary text-white rounded-full text-sm hover:bg-red-600 transition-colors">
                              选座购票
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
