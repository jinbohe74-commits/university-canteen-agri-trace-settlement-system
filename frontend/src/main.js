import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/global.css'

/* 首屏同步确定明度模式：全站唯一设计系统 + 用户全局选择，与当前路由无关。
   必须在 mount 前设置，否则所有 var() 失效会白屏。 */
{
  const saved = localStorage.getItem('ct_ui_mode')
  const mode = saved === 'paper' || saved === 'night' ? saved : 'day'
  document.documentElement.dataset.mode = mode
  if (localStorage.getItem('ct_reduce_motion') === '1') {
    document.documentElement.classList.add('reduce-motion')
  }
}

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.mount('#app')
