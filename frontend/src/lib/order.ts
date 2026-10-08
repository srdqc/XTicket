import type { OrderItem } from '@/types'
import { imgUrlReplace } from '@/lib/utils'

/** Keep legacy aliases isolated here while all UI consumes the canonical contract. */
export function getOrderDisplay(order: OrderItem) {
  return {
    activityName: order.activityName || order.movieName || '活动',
    venueName: order.venueName || order.cinemaName || '场馆',
    activityCoverUrl: imgUrlReplace(order.activityCoverUrl || order.movieImg || ''),
  }
}
