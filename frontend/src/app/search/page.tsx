'use client'

import { Suspense, useState, useCallback, useEffect } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Search, X, ArrowLeft } from 'lucide-react'
import { useHomeStore } from '@/store/home'
import Loading from '@/components/Loading'
import api from '@/lib/api'
import type { ActivityItem, SearchResponse, VenueItem } from '@/types'

type SearchType = 'all' | 'activity' | 'venue'

function SearchContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { posId } = useHomeStore()
  const [keywords, setKeywords] = useState(searchParams.get('keyword') || searchParams.get('kw') || '')
  const [searchType, setSearchType] = useState<SearchType>('all')
  const [results, setResults] = useState<SearchResponse>({})
  const [timer, setTimer] = useState<ReturnType<typeof setTimeout> | null>(null)

  const runSearch = useCallback(
    (value: string, type: SearchType) => {
      if (timer) clearTimeout(timer)

      if (!value.trim()) {
        setResults({})
        return
      }

      const t = setTimeout(() => {
        const cityId = posId ?? 1
        api
          .search({ keyword: value, cityId, type })
          .then((res) => setResults(res.data || {}))
          .catch(() => {})
      }, 300)
      setTimer(t)
    },
    [posId, timer]
  )

  const handleInput = useCallback(
    (value: string) => {
      setKeywords(value)
      runSearch(value, searchType)
    },
    [runSearch, searchType]
  )

  const handleTypeChange = (type: SearchType) => {
    setSearchType(type)
    runSearch(keywords, type)
  }

  useEffect(() => {
    if (keywords.trim()) {
      runSearch(keywords, searchType)
    }
    // 初始 URL keyword 只触发一次，后续由输入和类型切换驱动。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const activities = results.activities?.list || []
  const venues = results.venues?.list || []
  const hasResults = activities.length > 0 || venues.length > 0

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top bar */}
      <div className="bg-white border-b border-gray-200 min-w-[1200px]">
        <div className="max-w-[1200px] mx-auto h-[60px] flex items-center gap-4">
          <ArrowLeft
            className="w-6 h-6 text-gray-500 cursor-pointer hover:text-primary"
            onClick={() => router.push('/')}
          />
          <div className="flex-1 flex items-center bg-gray-100 rounded-full px-4 py-2 max-w-[600px]">
            <Search className="w-5 h-5 text-gray-400 mr-2 flex-shrink-0" />
            <input
              type="text"
              placeholder="搜索活动、场馆"
              value={keywords}
              onChange={(e) => handleInput(e.target.value)}
              className="flex-1 bg-transparent outline-none text-sm"
              autoFocus
            />
            {keywords && (
              <X
                className="w-4 h-4 text-gray-400 cursor-pointer"
                onClick={() => {
                  setKeywords('')
                  setResults({})
                }}
              />
            )}
          </div>
        </div>
      </div>

      {/* Results */}
      <div className="max-w-[1200px] mx-auto mt-6">
        <div className="flex gap-3 mb-4">
          {([
            { key: 'all', label: '全部' },
            { key: 'activity', label: '活动' },
            { key: 'venue', label: '场馆' },
          ] as { key: SearchType; label: string }[]).map((item) => (
            <button
              key={item.key}
              onClick={() => handleTypeChange(item.key)}
              className={`px-4 py-1.5 rounded-full text-sm ${
                searchType === item.key
                  ? 'bg-primary text-white'
                  : 'bg-white text-gray-600 hover:text-primary border border-gray-200'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {!hasResults && keywords && (
          <div className="text-center py-20 text-gray-400">
            未找到"{keywords}"相关结果
          </div>
        )}
        {activities.length > 0 && (
          <ResultSection title="活动">
            {activities.map((item) => (
              <ActivityResult key={item.id} item={item} onClick={() => router.push(`/activities/${item.id}`)} />
            ))}
          </ResultSection>
        )}
        {venues.length > 0 && (
          <ResultSection title="场馆">
            {venues.map((item) => (
              <VenueResult key={item.id} item={item} onClick={() => router.push(`/venues/${item.id}`)} />
            ))}
          </ResultSection>
        )}
      </div>
    </div>
  )
}

function ResultSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white rounded shadow-sm mb-5">
      <div className="px-6 py-3 border-b border-gray-100 text-sm font-medium text-gray-700">{title}</div>
      {children}
    </div>
  )
}

function ActivityResult({ item, onClick }: { item: ActivityItem; onClick: () => void }) {
  return (
    <div onClick={onClick} className="py-4 px-6 border-b border-gray-100 text-sm hover:bg-gray-50 cursor-pointer flex gap-4">
      {item.coverUrl && <img src={item.coverUrl} alt={item.name} className="w-12 h-16 object-cover rounded" />}
      <div className="min-w-0">
        <div className="font-medium text-gray-800">{item.name}</div>
        <div className="text-gray-400 mt-1">{item.category || item.publishDescription || '活动'}</div>
      </div>
    </div>
  )
}

function VenueResult({ item, onClick }: { item: VenueItem; onClick: () => void }) {
  return (
    <div onClick={onClick} className="py-4 px-6 border-b border-gray-100 text-sm hover:bg-gray-50 cursor-pointer">
      <div className="font-medium text-gray-800">{item.name}</div>
      {item.address && <div className="text-gray-400 mt-1">{item.address}</div>}
    </div>
  )
}

export default function SearchPage() {
  return (
    <Suspense fallback={<Loading />}>
      <SearchContent />
    </Suspense>
  )
}
