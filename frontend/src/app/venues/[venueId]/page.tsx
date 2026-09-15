'use client'

import { useState, useEffect } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { ArrowLeft, ChevronLeft, ChevronRight } from 'lucide-react'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { ActivityItem, VenueDetail, SessionItem } from '@/types'

export default function VenueDetailPage() {
  const router = useRouter()
  const params = useParams()
  const venueId = params.venueId as string

  const [venue, setVenue] = useState<VenueDetail | null>(null)
  const [activities, setActivities] = useState<ActivityItem[]>([])
  const [selectedActivityId, setSelectedActivityId] = useState<number | null>(null)
  const [dates, setDates] = useState<string[]>([])
  const [activeDate, setActiveDate] = useState('')
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [loading, setLoading] = useState(true)
  const [scheduleLoading, setScheduleLoading] = useState(false)

  // 加载场馆信息 + 活动列表
  useEffect(() => {
    if (!venueId) return
    const vid = Number(venueId)
    Promise.all([
      api.getVenueDetail(vid),
      api.getVenueActivities(vid),
    ])
      .then(([venueRes, activitiesRes]) => {
        setVenue(venueRes.data || null)
        const activityList = activitiesRes.data || []
        setActivities(activityList)
        if (activityList.length > 0) {
          setSelectedActivityId(activityList[0].id)
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [venueId])

  // 选中活动变化 → 加载日期
  useEffect(() => {
    if (!venueId || !selectedActivityId) return
    api.getVenueActivityAvailableDates(Number(venueId), selectedActivityId)
      .then((res) => {
        const dateList = res.data || []
        setDates(dateList)
        if (dateList.length > 0) {
          setActiveDate(dateList[0])
          loadSessions(dateList[0])
        } else {
          setDates([])
          setSessions([])
        }
      })
      .catch(() => {
        setDates([])
        setSessions([])
      })
  }, [venueId, selectedActivityId])

  const loadSessions = (date: string) => {
    if (!venueId || !selectedActivityId) return
    setScheduleLoading(true)
    api.getActivitySessions(selectedActivityId, { venueId: Number(venueId), showDate: date })
      .then((res) => setSessions(res.data || []))
      .catch(() => setSessions([]))
      .finally(() => setScheduleLoading(false))
  }

  const handleDateSelect = (date: string) => {
    setActiveDate(date)
    loadSessions(date)
  }

  const handleSelectSession = (session: SessionItem) => {
    router.push(`/seat-selection?scheduleId=${session.sessionId}&activityId=${selectedActivityId}`)
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

  const selectedActivity = activities.find((a) => a.id === selectedActivityId) || null

  if (loading) return <Loading />
  if (!venue) return <div className="text-center py-20 text-gray-400">场馆不存在</div>

  // 服务标签
  const services: { tag: string; desc: string; color: string }[] = []
  if (venue.allowRefund) services.push({ tag: '退', desc: '活动开始前可取消', color: 'orange' })
  if (venue.endorse) services.push({ tag: '改签', desc: '活动开始前可调整', color: 'orange' })
  if (venue.snack) services.push({ tag: '小吃', desc: '提供小吃饮品服务', color: 'blue' })
  venue.hallTypes?.forEach((h) => services.push({ tag: h, desc: `${h}会场`, color: 'blue' }))

  return (
    <div className="min-h-screen bg-white pb-20">
      {/* 场馆信息头部 */}
      <div className="w-full bg-[#392f59] text-white py-8 min-w-[1200px]">
        <div className="max-w-[1200px] mx-auto px-4 flex gap-8">
          <div className="flex-1">
            <div className="flex items-center gap-3 mb-3">
              <ArrowLeft
                className="w-6 h-6 cursor-pointer hover:text-gray-300 shrink-0"
                onClick={() => router.back()}
              />
              <h1 className="text-2xl font-bold">{venue.name}</h1>
            </div>
            <p className="text-gray-300 text-sm mb-2">{venue.address}</p>

            {services.length > 0 && (
              <div className="mt-4">
                <h3 className="font-bold text-sm mb-2">场馆服务</h3>
                <div className="space-y-1.5">
                  {services.map((s, idx) => (
                    <div key={idx} className="flex text-xs items-center">
                      <span
                        className={`border px-1.5 py-0.5 rounded mr-2 ${
                          s.color === 'orange'
                            ? 'border-orange-400 text-orange-400'
                            : 'border-gray-400 text-gray-400'
                        }`}
                      >
                        {s.tag}
                      </span>
                      <span className="text-gray-300">{s.desc}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="max-w-[1200px] mx-auto px-4 mt-6">
        {/* 面包屑 */}
        <div className="text-sm text-gray-500 mb-6">
          <span className="cursor-pointer hover:text-primary" onClick={() => router.push('/')}>
            XTicket
          </span>
          {' > '}
          <span className="cursor-pointer hover:text-primary" onClick={() => router.back()}>
            场馆
          </span>
          {' > '}
          <span className="text-gray-800">{venue.name}</span>
        </div>

        {/* 活动横向滑块 */}
        {activities.length > 0 && (
          <div className="relative w-full bg-gray-50 rounded-lg overflow-hidden mb-6">
            {/* 背景模糊 */}
            {selectedActivity && (
              <div
                className="absolute inset-0 bg-cover bg-center blur-2xl opacity-20"
                style={{ backgroundImage: `url(${selectedActivity.coverUrl})` }}
              />
            )}

            <div className="relative z-10 flex items-center py-6 px-4">
              <div className="flex items-end gap-5 overflow-x-auto hide-scrollbar px-8 py-2 mx-auto">
                {activities.map((activity) => {
                  const isSelected = activity.id === selectedActivityId
                  return (
                    <div
                      key={activity.id}
                      onClick={() => setSelectedActivityId(activity.id)}
                      className={`flex-shrink-0 transition-all duration-300 cursor-pointer border-2 rounded overflow-hidden ${
                        isSelected
                          ? 'w-[120px] h-[170px] border-white shadow-xl scale-110 z-10'
                          : 'w-[100px] h-[140px] border-transparent opacity-70 grayscale hover:grayscale-0 hover:opacity-100'
                      }`}
                    >
                      <img
                        src={activity.coverUrl}
                        className="w-full h-full object-cover"
                        alt={activity.name}
                      />
                    </div>
                  )
                })}
              </div>
            </div>
          </div>
        )}

        {/* 选中活动信息 */}
        {selectedActivity && (
          <div className="text-center border-b border-gray-200 pb-6 mb-6">
            <div className="flex items-center justify-center gap-3 mb-1">
              <h2 className="text-2xl font-bold text-gray-800">{selectedActivity.name}</h2>
              {selectedActivity.score && Number(selectedActivity.score) > 0 && (
                <span className="text-[#ff9900] text-xl font-bold">
                  {Number(selectedActivity.score).toFixed(1)}分
                </span>
              )}
            </div>
            <div className="text-sm text-gray-500 space-x-4">
              {selectedActivity.category && <span>类型：{selectedActivity.category}</span>}
              {selectedActivity.duration && <span>时长：{selectedActivity.duration}分钟</span>}
            </div>
          </div>
        )}

        {/* 日期选择 */}
        {dates.length > 0 && (
          <div className="flex gap-6 mb-6 border-b border-gray-100">
            {dates.map((d) => (
              <button
                key={d}
                onClick={() => handleDateSelect(d)}
                className={`pb-2 border-b-2 text-sm transition-colors ${
                  d === activeDate
                    ? 'border-primary text-primary font-medium'
                    : 'border-transparent text-gray-600 hover:text-primary'
                }`}
              >
                {formatDateLabel(d)}
              </button>
            ))}
          </div>
        )}

        {/* 排片表格 */}
        {scheduleLoading ? (
          <Loading />
        ) : sessions.length === 0 ? (
          <div className="text-center py-16 text-gray-400">
            {activities.length === 0 ? '该场馆暂无场次' : '当日暂无场次'}
          </div>
        ) : (
          <div className="w-full">
            <table className="w-full">
              <thead className="bg-gray-50 h-12 text-gray-500 font-normal text-sm">
                <tr>
                  <th className="text-left pl-8 w-[18%]">场次时间</th>
                  <th className="text-left w-[15%]">语言版本</th>
                  <th className="text-left w-[15%]">会场</th>
                  <th className="text-left w-[15%]">售价（元）</th>
                  <th className="text-right pr-8">选座购票</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((session, idx) => (
                  <tr
                    key={session.sessionId}
                    className={`h-20 ${idx % 2 === 0 ? 'bg-white' : 'bg-[#f9f9f9]'}`}
                  >
                    <td className="pl-8">
                      <div className="text-xl font-bold text-gray-900">{session.showTime}</div>
                      <div className="text-xs text-gray-400">{session.endTime}结束</div>
                    </td>
                    <td className="text-gray-700">{session.language}</td>
                    <td className="text-gray-700">{session.hallName}</td>
                    <td className="text-primary font-bold text-lg">¥{session.price}</td>
                    <td className="text-right pr-8">
                      <button
                        onClick={() => handleSelectSession(session)}
                        className="bg-white border border-primary text-primary hover:bg-primary hover:text-white transition rounded-full px-6 py-1.5 text-sm font-medium shadow-sm"
                      >
                        选座购票
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
