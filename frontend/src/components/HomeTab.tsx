'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import ActivityCard from '@/components/ActivityCard'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { ActivityItem } from '@/types'

export default function HomeTab() {
  const [hotActivities, setHotActivities] = useState<ActivityItem[]>([])
  const [comingActivities, setComingActivities] = useState<ActivityItem[]>([])
  const [loading, setLoading] = useState(true)
  const [activeCategory, setActiveCategory] = useState('全部')

  const categories = ['全部', '演唱会', '音乐节', '体育赛事', '电竞赛事', '展览', '舞台演出']
  const matchesCategory = (activity: ActivityItem) => {
    if (activeCategory === '全部') return true
    const category = activity.category || ''
    const aliases: Record<string, string[]> = {
      '展览': ['展'],
      '舞台演出': ['剧', '演出', '魔术'],
      '体育赛事': ['体育', '赛事', '跑步', '舞蹈'],
    }
    return (aliases[activeCategory] || [activeCategory]).some((keyword) => category.includes(keyword))
  }
  const visibleOnSale = hotActivities.filter(matchesCategory)
  const visibleUpcoming = comingActivities.filter(matchesCategory)

  useEffect(() => {
    Promise.all([
      api.getActivities({ status: 1 }),
      api.getActivities({ status: 0 }),
    ])
      .then(([hotRes, comingRes]) => {
        setHotActivities(hotRes.data?.activities?.slice(0, 8) || [])
        setComingActivities(comingRes.data?.activities?.slice(0, 8) || [])
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const router = useRouter()

  if (loading) return <Loading />

  return (
    <div>
      <div className="mb-8 flex flex-wrap items-center gap-3 border border-gray-100 bg-white p-4 shadow-sm">
        <span className="mr-2 text-sm text-gray-400">活动分类</span>
        {categories.map((category) => (
          <button
            key={category}
            onClick={() => setActiveCategory(category)}
            className={`rounded-full px-4 py-1.5 text-sm transition ${activeCategory === category ? 'bg-primary text-white' : 'bg-gray-50 text-gray-600 hover:text-primary'}`}
          >
            {category}
          </button>
        ))}
      </div>
      <div className="flex gap-10">
      {/* LEFT COLUMN: Activities */}
      <div className="flex-1">
        {/* 精选活动 */}
        <div className="mb-12">
          <div className="flex justify-between items-end mb-6">
            <h2 className="text-2xl text-primary font-normal">
              售票中{' '}
              <span className="text-2xl text-primary ml-1">
                （{visibleOnSale.length}个）
              </span>
            </h2>
          </div>

          <div className="grid grid-cols-4 gap-6">
            {visibleOnSale.map((activity) => (
              <div key={activity.id} className="flex flex-col items-center">
                <ActivityCard activity={activity} showButton={false} />
                <button
                  onClick={() => router.push(`/activities/${activity.id}`)}
                  className="w-full max-w-[160px] py-1 text-primary bg-white border border-gray-200 shadow-sm rounded-full hover:bg-primary hover:text-white transition-colors text-sm -mt-2"
                >
                  查看场次
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* 更多活动 */}
        <div>
          <div className="flex justify-between items-end mb-6 border-b border-gray-100 pb-2">
            <h2 className="text-2xl text-secondary font-normal">
              即将开售{' '}
              <span className="text-2xl text-secondary ml-1">
                （{visibleUpcoming.length}个）
              </span>
            </h2>
          </div>

          <div className="grid grid-cols-4 gap-6">
            {visibleUpcoming.map((activity) => (
              <div key={activity.id} className="flex flex-col items-center">
                <ActivityCard activity={activity} showButton={true} />
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* RIGHT COLUMN: Sidebar */}
      <div className="w-[360px] shrink-0">
        {/* 关注较多 */}
        <div className="mb-10">
          <div className="flex justify-between items-end mb-4">
            <h3 className="text-xl text-gold">关注较多</h3>
          </div>

          {/* Top 1 Large */}
          {comingActivities[0] && (
            <div onClick={() => router.push(`/activities/${comingActivities[0].id}`)} className="mb-4 bg-white border border-gray-100 p-0 relative group cursor-pointer overflow-hidden">
              <div className="w-full h-40 overflow-hidden relative">
                <img
                  src={comingActivities[0].coverUrl}
                  className="w-full object-cover -mt-10"
                  alt="Top1"
                />
                <div className="absolute top-0 left-2 bg-gold text-white w-6 h-6 flex items-center justify-center font-bold shadow-md text-sm">
                  1
                </div>
                <div className="absolute bottom-0 w-full bg-gradient-to-t from-black/70 to-transparent p-2 text-white">
                  <div className="font-bold">{comingActivities[0].name}</div>
                  <div className="text-xs text-gray-200">
                    活动信息：{comingActivities[0].comingTitle || comingActivities[0].publishDescription}
                  </div>
                  <div className="text-xs text-gold">
                    {comingActivities[0].followCount}人关注
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Row of 2 & 3 */}
          {comingActivities.length >= 3 && (
            <div className="flex gap-3 mb-4">
              {[comingActivities[1], comingActivities[2]].map((m, idx) => (
                <div key={m.id} onClick={() => router.push(`/activities/${m.id}`)} className="flex-1 relative cursor-pointer">
                  <div className="w-full h-28 overflow-hidden relative bg-gray-100">
                    <img
                      src={m.coverUrl}
                      className="w-full h-full object-cover"
                      alt=""
                    />
                    <div className="absolute top-0 left-0 bg-gold text-white w-5 h-5 flex items-center justify-center text-xs font-bold shadow">
                      {idx + 2}
                    </div>
                  </div>
                  <div className="mt-1">
                    <h4 className="font-bold text-sm truncate">{m.name}</h4>
                    <p className="text-xs text-gold">{m.followCount}人关注</p>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* List 4-8 */}
          <ul className="space-y-4">
            {comingActivities.slice(3).map((m, idx) => (
              <li
                key={m.id}
                onClick={() => router.push(`/activities/${m.id}`)}
                className="flex justify-between items-center text-sm cursor-pointer hover:text-primary"
              >
                <div className="flex items-center">
                  <span className="text-gray-400 italic mr-3 w-3">
                    {idx + 4}
                  </span>
                  <span className="text-gray-600">{m.name}</span>
                </div>
                <span className="text-gold text-xs">{m.followCount}人关注</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
      </div>
    </div>
  )
}
