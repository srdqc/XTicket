'use client'

import { useState, useEffect, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import ActivityCard from '@/components/ActivityCard'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { ActivityItem } from '@/types'

type SubTab = 'featured' | 'more'
type SortType = 'hot' | 'time'

const CATEGORIES = ['全部', '演唱会', '音乐节', '体育赛事', '电竞赛事', '艺术展', '舞台剧', '音乐会', '文化讲座']

export default function ActivitiesTab() {
  const router = useRouter()
  const [subTab, setSubTab] = useState<SubTab>('featured')
  const [sortType, setSortType] = useState<SortType>('hot')
  const [activeCategory, setActiveCategory] = useState('全部')
  const [activities, setActivities] = useState<ActivityItem[]>([])
  const [loading, setLoading] = useState(true)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)

  const fetchActivities = useCallback((p: number = 1) => {
    setLoading(true)
    const params: any = {
      status: subTab === 'featured' ? 1 : 0,
      sort: sortType,
      page: p,
      pageSize: 30,
    }
    if (activeCategory !== '全部') params.category = activeCategory

    api.getActivities(params)
      .then((res) => {
        const data = res.data || {}
        const list = data.activities || []
        if (p === 1) {
          setActivities(list)
        } else {
          setActivities((prev) => [...prev, ...list])
        }
        setTotal(data.total || 0)
        setHasMore(data.hasMore || false)
        setPage(p)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [subTab, sortType, activeCategory])

  useEffect(() => {
    fetchActivities(1)
  }, [fetchActivities])

  const handleLoadMore = () => {
    if (hasMore && !loading) {
      fetchActivities(page + 1)
    }
  }

  return (
    <div>
      {/* Sub Navigation */}
      <div className="bg-[#47464a] h-[60px] flex items-center justify-center mb-8 -mx-[calc((100vw-1200px)/2)] min-w-[1200px]">
        <div className="flex space-x-12">
          <button
            onClick={() => { setSubTab('featured'); setPage(1) }}
            className={`h-[60px] px-2 font-medium ${
              subTab === 'featured'
                ? 'text-primary border-b-2 border-primary'
                : 'text-gray-300 hover:text-white transition-colors'
            }`}
          >
            售票中
          </button>
          <button
            onClick={() => { setSubTab('more'); setPage(1) }}
            className={`h-[60px] px-2 font-medium ${
              subTab === 'more'
                ? 'text-primary border-b-2 border-primary'
                : 'text-gray-300 hover:text-white transition-colors'
            }`}
          >
          即将开售
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="mb-8 space-y-4 border border-gray-200 p-4 rounded-sm text-sm text-gray-600">
        <FilterRow
          label="类型："
          options={CATEGORIES}
          active={activeCategory}
          onSelect={setActiveCategory}
        />
      </div>

      {/* Sort Bar + Total */}
      <div className="flex items-center justify-between mb-6 mt-10">
        <div className="flex space-x-6 text-sm text-gray-500">
          <SortRadio label="按热门排序" active={sortType === 'hot'} onClick={() => setSortType('hot')} />
          <SortRadio label="按时间排序" active={sortType === 'time'} onClick={() => setSortType('time')} />
        </div>
        <span className="text-sm text-gray-400">共 {total} 个</span>
      </div>

      {/* Grid */}
      {loading && activities.length === 0 ? (
        <Loading />
      ) : activities.length === 0 ? (
        <div className="text-center py-20 text-gray-400">暂无相关活动</div>
      ) : (
        <>
          <div className="grid grid-cols-6 gap-x-8 gap-y-10">
            {activities.map((activity) => (
              <div
                key={activity.id}
                className="flex flex-col items-center cursor-pointer"
                onClick={() => router.push(`/activities/${activity.id}`)}
              >
                <ActivityCard activity={activity} showButton={false} />
                <div className="text-center w-full mt-[-20px]">
                  <h3 className="truncate font-medium text-gray-800 text-[16px] mb-1">
                    {activity.name}
                  </h3>
                  <div className={activity.released ? 'text-primary text-sm' : 'text-gold text-sm'}>
                    {activity.released ? '售票中' : '即将开售'} · {activity.followCount || 0}人关注
                  </div>
                </div>
              </div>
            ))}
          </div>

          {/* Load More */}
          {hasMore && (
            <div className="text-center py-8">
              <button
                onClick={handleLoadMore}
                disabled={loading}
                className="px-8 py-2 border border-gray-300 text-gray-500 rounded-full hover:bg-gray-50 transition-colors text-sm"
              >
                {loading ? '加载中...' : '加载更多'}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function FilterRow({
  label,
  options,
  active,
  onSelect,
}: {
  label: string
  options: string[]
  active: string
  onSelect: (v: string) => void
}) {
  return (
    <div className="flex items-start">
      <span className="w-16 text-gray-400">{label}</span>
      <div className="flex flex-wrap gap-4 flex-1">
        {options.map((opt) => (
          <span
            key={opt}
            onClick={() => onSelect(opt)}
            className={`cursor-pointer ${
              opt === active
                ? 'bg-primary text-white px-2 rounded-xl'
                : 'hover:text-primary'
            }`}
          >
            {opt}
          </span>
        ))}
      </div>
    </div>
  )
}

function SortRadio({
  label,
  active,
  onClick,
}: {
  label: string
  active: boolean
  onClick: () => void
}) {
  return (
    <div className="flex items-center space-x-1 cursor-pointer" onClick={onClick}>
      <div className="w-4 h-4 rounded-full border border-gray-300 flex items-center justify-center">
        {active && <div className="w-2 h-2 bg-primary rounded-full" />}
      </div>
      <span className={active ? 'text-black' : ''}>{label}</span>
    </div>
  )
}
