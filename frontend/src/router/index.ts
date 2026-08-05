/**
 * 라우터 설정.
 * `/` 등록, `/lookup` 사감 조회, 그 외는 안내 페이지.
 */

import { createRouter, createWebHistory } from 'vue-router'

import RegisterView from '../views/RegisterView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'register',
      component: RegisterView,
      meta: { title: '야간 복귀 등록' },
    },
    {
      path: '/lookup',
      name: 'lookup',
      component: () => import('../views/LookupView.vue'),
      meta: { title: '야간 복귀 명단 조회' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '페이지를 찾을 수 없습니다' },
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · 기숙사 야간 복귀` : '기숙사 야간 복귀 등록'
})

export default router
