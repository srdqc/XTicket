// ==================== 活动相关 ====================
export interface ActivityItem {
  id: number
  name: string
  coverUrl: string
  score?: number | string
  category?: string
  source?: string
  duration?: number
  publishDescription?: string
  followCount?: number
  released?: boolean
  releaseYear?: number
  showInfo?: string
  comingTitle?: string
}

export interface ActivityDetail {
  id: number
  name: string
  englishName?: string
  coverUrl: string
  score?: number | string
  category?: string
  source?: string
  duration?: number
  publishDescription?: string
  description?: string
  followCount?: number
  released?: boolean
  releaseYear?: number
  videoUrl?: string
  photos?: string[]
  photoCount?: number
}

// ==================== 城市相关 ====================
export interface CityItem {
  id: number
  nm: string
  py: string
}

export interface CityGroup {
  tag: string
  items: CityItem[]
}

// ==================== 场馆相关 ====================
export interface VenueFeatures {
  allowRefund?: boolean
  endorse?: boolean
  snack?: boolean
  vipTag?: string
  hallTypes?: string[]
}

export interface VenuePromotion {
  cardPromotionTag?: string
}

export interface VenueItem {
  id: number
  name: string
  address?: string
  distance?: string
  features?: VenueFeatures
  promotion?: VenuePromotion
}

export interface VenueDetail {
  id: number
  name: string
  address?: string
  allowRefund?: boolean
  endorse?: boolean
  snack?: boolean
  vipTag?: string
  hallTypes?: string[]
}

// ==================== 场次相关 ====================
export interface SessionItem {
  sessionId: number
  activityId: number
  venueId: number
  venueName?: string
  venueAddress?: string
  hallName: string
  showDate: string
  showTime: string
  endTime: string
  language?: string
  totalSeats: number
  availableSeats: number
  price: number
}

export interface VenueSessionGroup {
  venue: VenueItem
  sessions: SessionItem[]
}

// ==================== 座位相关 ====================
export interface SeatInfo {
  row: number
  col: number
  label: string
  status: number // 0=可选 1=已售 2=他人锁定 3=我锁定 -1=不可用
  couple: boolean
}

export interface SeatLayoutData {
  hallName: string
  hallType: string
  rows: number
  cols: number
  aisles: number[]
  coupleRows: number[]
  seats: SeatInfo[][]
}

export interface LockSeatsRequest {
  scheduleId: number
  seats: { row: number; col: number }[]
}

export interface LockSeatsResponse {
  lockToken: string
  lockUntil: string
  seatCount: number
  price: number
}

// ==================== 订单相关 ====================
export interface OrderItem {
  id: number
  orderNo: string
  movieName: string
  cinemaName: string
  hallName: string
  showTime: string
  seatCount: number
  seatsInfo: string
  unitPrice: number
  totalPrice: number
  status: number
  statusDesc: string
  createTime: string
  payTime?: string
  expireTime?: string
  scheduleId: number
  movieImg?: string
}

export interface CreateOrderRequest {
  scheduleId: number
  lockToken?: string
  seats?: { row: number; col: number }[]
  seatCount: number
  seatsInfo: string
}

// ==================== 电子票相关 ====================
export interface TicketItem {
  ticketNo: string
  orderNo: string
  sessionId: number
  status: number
  statusDesc: string
  issuedAt: string
  usedAt?: string
  activityName: string
  venueName: string
  hallName: string
  showTime: string
  seatLabel: string
  rowNum: number
  colNum: number
}

export interface CheckInResult {
  ticketNo: string
  sessionId: number
  status: number
  usedAt: string
  firstCheckIn: boolean
  alreadyUsed: boolean
  activityName: string
  venueName: string
  hallName: string
  showTime: string
  seatLabel: string
}

// ==================== 搜索相关 ====================
export interface SearchParams {
  keyword: string | number
  cityId: number
  type?: 'all' | 'activity' | 'venue'
}

export interface SearchResponse {
  activities?: {
    list: ActivityItem[]
  }
  venues?: {
    list: VenueItem[]
  }
}

export interface FollowStatus {
  followed: boolean
  followCount: number
}

// ==================== Tab 类型 ====================
export type Tab = 'home' | 'activities' | 'venues'

// ==================== 用户相关 ====================
export interface UserAccountInfo {
  account: string
  password: string
  userNick?: string
  userHeadImg?: string
  likeList?: string[]
}
