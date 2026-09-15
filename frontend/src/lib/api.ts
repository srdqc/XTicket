import axios from 'axios'
import type { SearchParams, LockSeatsRequest, CreateOrderRequest } from '@/types'

// 需要鉴权的 axios 实例（指向 /api）
const authInstance = axios.create({
  baseURL: '/api',
})

// 请求拦截器 —— 为所有请求附带 JWT token
const attachToken = (config: any) => {
  if (typeof window !== 'undefined') {
    let token: string | null = null
    try {
      const stored = localStorage.getItem('maoyan-user')
      if (stored) {
        const parsed = JSON.parse(stored)
        token = parsed?.state?.token || null
      }
    } catch {}
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
}

authInstance.interceptors.request.use(attachToken)

const api = {
  /** 城市列表 */
  getCities: () =>
    axios
      .get('/dianying/cities.json')
      .then((res) => res.data),

  /** 搜索 */
  search: (params: SearchParams) =>
    authInstance.get('/search', { params }).then((res) => res.data),

  // ==================== 正式 Activity / Venue / Session Catalog API ====================

  /** 活动列表（支持 status/category/source/year/sort/page/pageSize 筛选） */
  getActivities: (params?: {
    status?: number | null
    category?: string
    source?: string
    year?: number | null
    sort?: string
    page?: number
    pageSize?: number
  }) => authInstance.get('/activities', { params }).then((res) => res.data),

  /** 活动详情 */
  getActivityDetail: (activityId: number | string) =>
    authInstance.get(`/activities/${activityId}`).then((res) => res.data),

  /** 活动场次列表（可选 venueId / showDate） */
  getActivitySessions: (activityId: number | string, params?: { venueId?: number; showDate?: string }) =>
    authInstance.get(`/activities/${activityId}/sessions`, { params }).then((res) => res.data),

  /** 活动场次（按场馆分组） */
  getActivitySessionsGroupedByVenue: (activityId: number | string, params?: { showDate?: string }) =>
    authInstance.get(`/activities/${activityId}/sessions/grouped-by-venue`, { params }).then((res) => res.data),

  /** 活动有场次的日期列表 */
  getActivityAvailableDates: (activityId: number | string) =>
    authInstance.get(`/activities/${activityId}/available-dates`).then((res) => res.data),

  /** 活动关注状态 */
  getActivityFollowStatus: (activityId: number | string) =>
    authInstance.get(`/activities/${activityId}/follow-status`).then((res) => res.data),

  /** 关注活动 */
  followActivity: (activityId: number | string) =>
    authInstance.post(`/activities/${activityId}/follow`).then((res) => res.data),

  /** 取消关注活动 */
  unfollowActivity: (activityId: number | string) =>
    authInstance.delete(`/activities/${activityId}/follow`).then((res) => res.data),

  /** 场馆列表 */
  getVenues: (params?: { cityId?: number; day?: string; offset?: number }) =>
    authInstance.get('/venues', { params }).then((res) => res.data),

  /** 场馆详情 */
  getVenueDetail: (venueId: number | string) =>
    authInstance.get(`/venues/${venueId}`).then((res) => res.data),

  /** 场馆下活动列表 */
  getVenueActivities: (venueId: number | string) =>
    authInstance.get(`/venues/${venueId}/activities`).then((res) => res.data),

  /** 场馆下指定活动的可选日期 */
  getVenueActivityAvailableDates: (venueId: number | string, activityId: number | string) =>
    authInstance.get(`/venues/${venueId}/activities/${activityId}/available-dates`).then((res) => res.data),

  // ==================== 座位相关 ====================

  /** 获取座位布局 */
  getSeatLayout: (params: { scheduleId: number }) =>
    authInstance.get('/seat/layout', { params }).then((res) => res.data),

  /** 锁定座位 */
  lockSeats: (data: LockSeatsRequest) =>
    authInstance.post('/seat/lock', data).then((res) => res.data),

  /** 释放座位 */
  unlockSeats: (params: { scheduleId: number }) =>
    authInstance.post('/seat/unlock', null, { params }).then((res) => res.data),

  // ==================== 订单相关 ====================

  /** 创建订单 */
  createOrder: (data: CreateOrderRequest) =>
    authInstance.post('/order/create', data).then((res) => res.data),

  /** 取消订单 */
  cancelOrder: (orderNo: string) =>
    authInstance.post(`/order/cancel/${orderNo}`).then((res) => res.data),

  /** 整单积分退款 */
  refundOrder: (orderNo: string) =>
    authInstance.post(`/order/refund/${encodeURIComponent(orderNo)}`).then((res) => res.data),

  /** 查询用户订单列表 */
  getUserOrders: (params: { page?: number; size?: number }) =>
    authInstance.get('/order/list', { params }).then((res) => res.data),

  // ==================== 支付相关 ====================

  /** 模拟支付 */
  payOrder: (params: { orderNo: string }) =>
    authInstance.post('/payment/pay', null, { params }).then((res) => res.data),

  /** 查询订单详情 */
  getOrderDetail: (params: { orderNo: string }) =>
    authInstance.get('/payment/orderDetail', { params }).then((res) => res.data),

  // ==================== 电子票相关 ====================

  /** 查询当前用户电子票 */
  getTickets: (orderNo?: string) =>
    authInstance.get('/tickets', { params: orderNo ? { orderNo } : undefined }).then((res) => res.data),

  /** 查询当前用户单张电子票 */
  getTicket: (ticketNo: string) =>
    authInstance.get(`/tickets/${ticketNo}`).then((res) => res.data),

  /** 工作人员核销电子票 */
  checkInTicket: (ticketNo: string, sessionId: number) =>
    authInstance.post(`/checkin/tickets/${encodeURIComponent(ticketNo)}`, { sessionId }).then((res) => res.data),

  // ==================== 认证相关 ====================

  /** 用户登录 */
  login: (data: { account: string; password: string }) =>
    axios.post('/api/auth/login', data).then((res) => res.data),

  /** 用户注册 */
  register: (data: { account: string; password: string; userNick?: string; inviteCode?: string }) =>
    axios.post('/api/auth/register', data).then((res) => res.data),

  /** 获取当前用户信息（含积分） */
  getUserInfo: () => {
    let token: string | null = null
    try {
      const stored = localStorage.getItem('maoyan-user')
      if (stored) {
        const parsed = JSON.parse(stored)
        token = parsed?.state?.token || null
      }
    } catch {}
    return axios.get('/api/auth/me', {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    }).then((res) => res.data)
  },
}

export default api
