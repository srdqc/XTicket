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
  const isOnSale = activity.released

  return (
    <div
      className="w-[160px] flex flex-col mb-6 group cursor-pointer"
      onClick={() => router.push(`/activities/${activity.id}`)}
    >
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

        {/* Activity status and name */}
        <div className="absolute bottom-0 left-0 w-full bg-gradient-to-t from-black/80 to-transparent p-2 pt-6 flex justify-between items-end">
          <span className="text-white text-xs font-medium truncate w-full">
            {activity.name}
          </span>
          <span className={`text-[10px] px-1.5 py-0.5 rounded absolute bottom-1 right-2 ${isOnSale ? 'bg-primary text-white' : 'bg-amber-400 text-white'}`}>
            {isOnSale ? '售票中' : '即将开售'}
          </span>
        </div>
      </div>

      {/* Below Image Content */}
      {showButton && (
        <div className="mt-2 flex items-center justify-between text-sm">
          {isOnSale ? (
            <button className="w-full py-1.5 mt-1 border border-gray-200 text-primary rounded hover:bg-primary hover:text-white transition-colors text-sm">
              购票
            </button>
          ) : (
            <div className="w-full">
              <div className="flex justify-between items-center text-xs text-gray-500 mb-1">
                <span className="text-gold">{activity.followCount}人关注</span>
              </div>
              <button
                onClick={(event) => { event.stopPropagation(); router.push(`/activities/${activity.id}`) }}
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
