'use client'

import { useRouter } from 'next/navigation'
import { imgUrlReplace } from '@/lib/utils'
import type { ActivityItem } from '@/types'

interface ActivityCardProps {
  activity: ActivityItem
  showButton?: boolean
}

export default function ActivityCard({ activity, showButton = true }: ActivityCardProps) {
  const router = useRouter()
  const isReleased = activity.released

  return (
    <div className="w-[160px] flex flex-col mb-6 group cursor-pointer">
      <div className="relative w-full h-[220px] overflow-hidden bg-gray-200 shadow-sm">
        <img
          src={imgUrlReplace(activity.coverUrl)}
          alt={activity.name}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        />
        {/* Tags Overlay */}
        {activity.category && (
          <div className="absolute top-1 left-1 flex flex-col space-y-1">
            {activity.category.split(',').slice(0, 2).map((tag, idx) => (
              <span
                key={idx}
                className={`text-[10px] text-white px-1 py-0.5 rounded-sm font-bold shadow-sm ${
                  tag.includes('IMAX') ? 'bg-blue-600' : 'bg-primary'
                }`}
              >
                {tag}
              </span>
            ))}
          </div>
        )}

        {/* Rating / Want-to-follow Overlay at Bottom */}
        <div className="absolute bottom-0 left-0 w-full bg-gradient-to-t from-black/80 to-transparent p-2 pt-6 flex justify-between items-end">
          <span className="text-white text-xs font-medium truncate w-full">
            {activity.name}
          </span>
          {isReleased && activity.score ? (
            <span className="text-gold font-bold italic text-base absolute bottom-1 right-2">
              {Number(activity.score).toFixed(1)}
            </span>
          ) : null}
        </div>
      </div>

      {/* Below Image Content */}
      {showButton && (
        <div className="mt-2 flex items-center justify-between text-sm">
          {isReleased ? (
            <button className="w-full py-1.5 mt-1 border border-gray-200 text-primary rounded hover:bg-primary hover:text-white transition-colors text-sm">
              购票
            </button>
          ) : (
            <div className="w-full">
              <div className="flex justify-between items-center text-xs text-gray-500 mb-1">
                <span className="text-gold">{activity.followCount}人关注</span>
              </div>
              <button
                onClick={() => router.push(`/activities/${activity.id}`)}
                className="w-full py-1.5 border border-gray-200 text-secondary rounded hover:bg-gray-50 text-xs"
              >
                查看详情
              </button>
              {activity.comingTitle && (
                <div className="text-center text-xs text-gray-400 mt-2">
                  {activity.comingTitle}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
