<script setup lang="ts">
import { computed, ref } from 'vue'
import AppLogo from '@/components/AppLogo.vue'
import { Lock, User } from '@element-plus/icons-vue'

import { useAuth } from '@/composables/useAuth'

const { login, register } = useAuth()

type Mode = 'login' | 'register'

const mode = ref<Mode>('login')
const identifier = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

const isRegister = computed(() => mode.value === 'register')
const canSubmit = computed(
  () => identifier.value.trim().length >= 3 && password.value.length >= 8 && !submitting.value,
)

function switchMode(next: Mode): void {
  if (mode.value === next) return
  mode.value = next
  // The message belongs to the attempt that produced it, not to the other form.
  errorMessage.value = ''
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const credentials = { identifier: identifier.value, password: password.value }
    const user = isRegister.value ? await register(credentials) : await login(credentials)
    // Nothing else to do: App.vue watches the session and renders the trip list.
    password.value = ''
    void user
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '操作失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}

const identifierPlaceholder = '用户名 / 邮箱 / 手机号，例如 traveler、you@example.com、13800138000'
</script>

<template>
  <main class="auth-shell">
    <section class="auth-card" aria-label="登录或注册">
      <div class="auth-brand"><AppLogo /><span>旅迹 AI</span></div>
      <h1>智能旅行规划平台</h1>
      <p class="auth-lede">
        登录后，你收藏的地点、每日行程和规划建议都只属于你自己的账号。
      </p>

      <div class="auth-modes" role="tablist" aria-label="登录方式">
        <button
          type="button"
          role="tab"
          class="auth-mode"
          :class="{ 'is-active': !isRegister }"
          :aria-selected="!isRegister"
          aria-label="切换到登录"
          @click="switchMode('login')"
        >登录</button>
        <button
          type="button"
          role="tab"
          class="auth-mode"
          :class="{ 'is-active': isRegister }"
          :aria-selected="isRegister"
          aria-label="切换到注册"
          @click="switchMode('register')"
        >注册</button>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label class="auth-field">
          <span>账号</span>
          <el-input
            v-model="identifier"
            :prefix-icon="User"
            :placeholder="identifierPlaceholder"
            aria-label="账号（用户名、邮箱或手机号）"
            autocomplete="username"
            name="identifier"
          />
        </label>

        <label class="auth-field">
          <span>密码</span>
          <el-input
            v-model="password"
            :prefix-icon="Lock"
            :type="showPassword ? 'text' : 'password'"
            :placeholder="isRegister ? '至少 8 位' : '请输入密码'"
            aria-label="密码"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            name="password"
          />
        </label>

        <el-checkbox v-model="showPassword" aria-label="显示密码">显示密码</el-checkbox>

        <p v-if="isRegister" class="auth-hint">
          使用用户名、邮箱或手机号中的任意一种注册。
        </p>

        <p v-if="errorMessage" class="auth-error" role="alert">{{ errorMessage }}</p>

        <el-button
          native-type="submit"
          type="primary"
          size="large"
          class="auth-submit"
          :loading="submitting"
          :disabled="!canSubmit"
          :aria-label="isRegister ? '注册并登录' : '登录'"
        >{{ isRegister ? '注册并登录' : '登录' }}</el-button>
      </form>

      <p class="auth-footnote">
        <template v-if="isRegister">已经有账号了？<button type="button" class="auth-link" @click="switchMode('login')">直接登录</button></template>
        <template v-else>还没有账号？<button type="button" class="auth-link" @click="switchMode('register')">用用户名、邮箱或手机号注册</button></template>
      </p>
    </section>
  </main>
</template>

<style scoped>
.auth-shell {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px 16px;
  background: #f7f8fa;
}
.auth-card {
  width: min(440px, 100%);
  padding: 32px;
  background: #ffffff;
  border: 0;
  border-radius: 12px;
  
}
.auth-brand { display:flex;align-items:center;gap:12px;margin-bottom:26px!important;font-size:20px!important;color:var(--text)!important; margin: 0 0 6px; color: #6b7890; font-size: 12px; font-weight: 700; letter-spacing: 0.04em; }
h1 { margin: 0 0 8px; color: #111827; font-size: 24px; }
.auth-lede { margin: 0 0 20px; color: #788398; font-size: 13px; line-height: 1.6; }
.auth-modes { display: flex; gap: 4px; padding: 4px; margin-bottom: 20px; background: #f0f4fb; border-radius: 10px; }
.auth-mode {
  flex: 1;
  padding: 8px 0;
  color: #49678f;
  font-size: 14px;
  font-weight: 600;
  background: transparent;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
}
.auth-mode.is-active { color: #111827; background: #ffffff; box-shadow: 0 1px 3px rgba(29, 51, 45, 0.12); }
.auth-form { display: flex; flex-direction: column; gap: 14px; }
.auth-field { display: flex; flex-direction: column; gap: 6px; color: #246bfd; font-size: 13px; font-weight: 600; }
.auth-hint { margin: 0; color: #788398; font-size: 12px; line-height: 1.6; }
.auth-error { margin: 0; padding: 8px 10px; color: #9f2f2f; font-size: 13px; background: #fdf2f2; border-radius: 8px; }
.auth-submit { width: 100%; }
.auth-footnote { margin: 18px 0 0; color: #788398; font-size: 13px; text-align: center; }
.auth-link { padding: 0; color: #246bfd; font-size: 13px; font-weight: 600; background: none; border: 0; cursor: pointer; text-decoration: underline; }
</style>
