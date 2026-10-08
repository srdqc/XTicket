import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import api from '@/lib/api'
import type { UserRole } from '@/types'

interface UserState {
  isLogged: boolean
  userNick: string
  userHeadImg: string | undefined
  defaultHeadImg: string
  token: string | null
  points: number
  role: UserRole | null

  loginAsync: (account: string, password: string) => Promise<{ success: boolean; msg?: string }>
  registerAsync: (account: string, password: string, userNick?: string, inviteCode?: string) => Promise<{ success: boolean; msg?: string }>
  logout: () => void
  setPoints: (points: number) => void
  fetchProfile: () => Promise<void>
}

export const useUserStore = create<UserState>()(
  persist(
    (set, get) => ({
      isLogged: false,
      userNick: '',
      userHeadImg: undefined,
      defaultHeadImg:
        '/images/avatar-default.svg',
      token: null,
      points: 0,
      role: null,

      loginAsync: async (account, password) => {
        try {
          const res = await api.login({ account, password })
          if (res.code === 200 && res.data) {
            set({
              isLogged: true,
              userNick: res.data.userNick || account,
              userHeadImg: res.data.userHeadImg || res.data.headImg || undefined,
              token: res.data.token,
              points: res.data.points ?? 0,
              role: res.data.role || 'USER',
            })
            return { success: true }
          }
          return { success: false, msg: res.message || '登录失败' }
        } catch (e: any) {
          const msg = e?.response?.data?.message || '网络错误，请稍后重试'
          return { success: false, msg }
        }
      },

      registerAsync: async (account, password, userNick, inviteCode) => {
        try {
          const res = await api.register({ account, password, userNick, inviteCode })
          if (res.code === 200) {
            return { success: true }
          }
          return { success: false, msg: res.message || '注册失败' }
        } catch (e: any) {
          const msg = e?.response?.data?.message || '网络错误，请稍后重试'
          return { success: false, msg }
        }
      },

      logout: () =>
        set({
          isLogged: false,
          userNick: '',
          userHeadImg: undefined,
          token: null,
          points: 0,
          role: null,
        }),

      setPoints: (points: number) => set({ points }),

      fetchProfile: async () => {
        try {
          const res = await api.getUserInfo()
          if (res.code === 200 && res.data) {
            set({
              points: res.data.points ?? 0,
              role: res.data.role || 'USER',
              userNick: res.data.userNick || get().userNick,
              userHeadImg: res.data.userHeadImg || get().userHeadImg,
            })
          }
        } catch {}
      },
    }),
    {
      // Compatibility key: changing it would silently sign out existing local users.
      name: 'maoyan-user',
      storage: createJSONStorage(() => localStorage),
    }
  )
)
