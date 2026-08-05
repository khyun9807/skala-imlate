/**
 * 애플리케이션 진입점.
 * 디자인 토큰 → 기본 스타일 순서로 로드한 뒤 Vue 앱을 마운트한다.
 */

import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/base.css'

createApp(App).use(router).mount('#app')
