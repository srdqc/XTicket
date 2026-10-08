'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import { ChevronDown, ChevronUp, Heart, Play } from 'lucide-react'
import api from '@/lib/api'
import { imgUrlReplace } from '@/lib/utils'
import type { ActivityDetail } from '@/types'
import { useUserStore } from '@/store/user'

interface Props {
  activityDetail: ActivityDetail
}

export default function TopIntro({ activityDetail }: Props) {
  const router = useRouter()
  const { isLogged } = useUserStore()
  const [showFullIntro, setShowFullIntro] = useState(false)
  const [showVideo, setShowVideo] = useState(false)
  const [followed, setFollowed] = useState(false)
  const [followCount, setFollowCount] = useState(activityDetail.followCount || 0)
  const [followLoading, setFollowLoading] = useState(false)

  useEffect(() => {
    api
      .getActivityFollowStatus(activityDetail.id)
      .then((res) => {
        if (res.code === 200 && res.data) {
          setFollowed(Boolean(res.data.followed))
          setFollowCount(res.data.followCount ?? 0)
        }
      })
      .catch(() => {})
  }, [activityDetail.id])

  const handleFollow = async () => {
    if (!isLogged) {
      toast.error('请先登录后再关注活动')
      router.push('/login')
      return
    }
    if (followLoading) return
    setFollowLoading(true)
    try {
      const res = followed
        ? await api.unfollowActivity(activityDetail.id)
        : await api.followActivity(activityDetail.id)
      if (res.code === 200 && res.data) {
        setFollowed(Boolean(res.data.followed))
        setFollowCount(res.data.followCount ?? followCount)
        toast.success(res.data.followed ? '已关注' : '已取消关注')
      } else {
        toast.error(res.message || '操作失败')
      }
    } catch (e: any) {
      const msg = e?.response?.data?.message || '操作失败'
      toast.error(msg)
    } finally {
      setFollowLoading(false)
    }
  }

  return (
    <div>
      {/* 活动头部信息 */}
      <div className="relative flex items-start h-[280px] overflow-hidden rounded-t">
        {/* 模糊背景 */}
        <div className="absolute inset-0 z-0">
          <img
            src={imgUrlReplace(activityDetail.coverUrl)}
            alt=""
            className="w-full h-full object-cover blur-[30px] scale-125"
          />
          <div className="absolute inset-0 bg-gray-800/60" />
        </div>

        {/* 海报 */}
        <div
          className="relative z-10 ml-8 mt-8 flex-shrink-0 cursor-pointer"
          onClick={() => activityDetail.videoUrl && setShowVideo(true)}
        >
          <img
            src={imgUrlReplace(activityDetail.coverUrl)}
            alt={activityDetail.name}
            className="w-[180px] h-[240px] rounded-lg object-cover shadow-lg"
          />
          {activityDetail.videoUrl && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="w-12 h-12 bg-black/40 rounded-full flex items-center justify-center hover:bg-black/60 transition">
                <Play className="w-6 h-6 text-white fill-white" />
              </div>
            </div>
          )}
        </div>

        {/* 文字信息 */}
        <div className="relative z-10 flex-1 mt-8 ml-8 mr-8 space-y-2 text-gray-200 min-w-0">
          <h1 className="text-white text-2xl font-bold">
            {activityDetail.name}
          </h1>
          {activityDetail.englishName && (
            <p className="text-white/70 text-sm">{activityDetail.englishName}</p>
          )}
          <div className="mt-2 flex items-center gap-3">
            <span className={`rounded-full px-3 py-1 text-xs font-medium ${activityDetail.released ? 'bg-primary text-white' : 'bg-amber-400 text-white'}`}>
              {activityDetail.released ? '售票中' : '即将开售'}
            </span>
            <span className="text-white/80 text-sm">{followCount} 人关注</span>
          </div>
          <button
            onClick={handleFollow}
            disabled={followLoading}
            className={`mt-3 inline-flex items-center gap-2 rounded-full px-5 py-2 text-sm font-medium transition ${
              followed
                ? 'bg-white/20 text-white hover:bg-white/30'
                : 'bg-primary text-white hover:bg-indigo-700'
            } disabled:opacity-60`}
          >
            <Heart className={`w-4 h-4 ${followed ? 'fill-white' : ''}`} />
            {followed ? '已关注' : '关注'}
            <span className="text-white/80">{followCount}</span>
          </button>
          <div className="pt-2 space-y-1.5 text-sm text-gray-300">
            {activityDetail.category && <p>活动类型：{activityDetail.category}</p>}
            {activityDetail.source && (
              <p>
                举办城市：{activityDetail.source}{activityDetail.duration ? ` · 活动时长：${activityDetail.duration}分钟` : ''}
              </p>
            )}
            {activityDetail.publishDescription && <p>{activityDetail.publishDescription}</p>}
          </div>
        </div>
      </div>

      {/* 简介 */}
      {activityDetail.description && (
        <div
          className="px-8 py-5 cursor-pointer border-b border-gray-100"
          onClick={() => setShowFullIntro(!showFullIntro)}
        >
          <h3 className="text-base font-medium mb-2">简介</h3>
          <p
            className={`text-sm text-gray-600 leading-relaxed ${
              !showFullIntro ? 'line-clamp-3' : ''
            }`}
          >
            {activityDetail.description}
          </p>
          <div className="flex justify-center mt-2 text-gray-400">
            {showFullIntro ? (
              <ChevronUp className="w-4 h-4" />
            ) : (
              <ChevronDown className="w-4 h-4" />
            )}
          </div>
        </div>
      )}

      {/* 视频播放遮罩 */}
      {showVideo && activityDetail.videoUrl && (
        <div
          className="fixed inset-0 z-[999] bg-black/70 flex items-center justify-center"
          onClick={() => setShowVideo(false)}
        >
          <video
            controls
            autoPlay
            src={activityDetail.videoUrl}
            className="max-w-[800px] w-full max-h-[60vh]"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  )
}
