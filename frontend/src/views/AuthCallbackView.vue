<template>
  <main class="auth-callback">
    <el-card>
      <h1>企业身份认证</h1>
      <p v-if="!error" role="status">正在完成登录并读取权限…</p>
      <template v-else>
        <el-alert type="error" :title="error" :closable="false" show-icon />
        <el-button type="primary" :loading="signingIn" @click="retry">重新登录</el-button>
        <el-button @click="manualLogin">手工令牌登录</el-button>
      </template>
    </el-card>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { EapApi } from '@/api/eap'
import { clearAccessToken, setAccessToken } from '@/api/auth'
import { finishOidcSignIn, startOidcSignIn } from '@/api/oidc'
import { loadCurrentUser } from '@/stores/permissions'

const router = useRouter()
const error = ref('')
const signingIn = ref(false)

async function retry() {
  signingIn.value = true
  try {
    const status = await EapApi.getPlatformStatus()
    if (!status.oidc) throw new Error('平台尚未配置企业登录，请使用手工令牌')
    await startOidcSignIn(status.oidc, '/')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请重试'
  } finally { signingIn.value = false }
}

async function manualLogin() {
  await router.replace('/')
  window.dispatchEvent(new Event('eap-auth-required'))
}

onMounted(async () => {
  const search = window.location.search
  window.history.replaceState(window.history.state, '', '/auth/callback')
  clearAccessToken()
  try {
    const status = await EapApi.getPlatformStatus()
    if (!status.oidc) throw new Error('平台尚未配置企业登录，请使用手工令牌')
    const result = await finishOidcSignIn(status.oidc, search)
    setAccessToken(result.accessToken)
    const user = await loadCurrentUser()
    if (!user) throw new Error('登录状态已改变，请重新登录')
    await router.replace(result.returnTo)
  } catch (cause) {
    clearAccessToken()
    error.value = cause instanceof Error ? cause.message : '登录失败，请重试'
  }
})
</script>

<style scoped>
.auth-callback { max-width: 560px; margin: 12vh auto; padding: 16px; }
.auth-callback .el-button { margin-top: 20px; }
</style>
