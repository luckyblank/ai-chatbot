<template>
  <main class="login-page">
    <section class="login-story" aria-label="企业智能服务中心介绍">
      <div class="story-art">
        <span class="story-robot-mark">AI</span>
        <span class="story-bubble story-bubble-efficient">更高效</span>
        <span class="story-bubble story-bubble-smart">更智能</span>
        <span class="story-bubble story-bubble-create">一起创造<br />更好的工作 <span>♥</span></span>
        <DocumentTextIcon class="story-data-icon story-data-list" aria-hidden="true" />
        <ChartBarIcon class="story-data-icon story-data-chart" aria-hidden="true" />
        <ChatBubbleLeftEllipsisIcon class="story-data-icon story-data-chat" aria-hidden="true" />
        <span class="story-platform-copy">AI · KNOWLEDGE · BUSINESS</span>
      </div>
      <div class="story-scene">
        <header class="story-header">
          <div class="story-brand">
            <span class="story-brand-icon" aria-hidden="true">
              <ChatBubbleLeftEllipsisIcon />
              <i></i>
            </span>
            <strong>企业智能服务中心</strong>
          </div>
          <div class="story-motto">让 AI 真正服务业务 · 与人共创更好的工作<span></span></div>
        </header>

        <div class="story-copy">
          <p class="story-eyebrow">ENTERPRISE AI SERVICE</p>
          <h1>把知识、对话与业务流程<br />放进同一个<span>工作台</span></h1>
          <p class="story-description">面向客服、客户成功、IT 服务与平台运营的企业级智能服务应用。</p>

          <ul class="story-features">
            <li>
              <span class="story-feature-icon story-feature-knowledge" aria-hidden="true"><CircleStackIcon /></span>
              <span><strong>可选知识库增强与引用溯源</strong><small>让企业知识真正被用起来</small></span>
            </li>
            <li>
              <span class="story-feature-icon story-feature-tools" aria-hidden="true"><ShareIcon /></span>
              <span><strong>工具调用和模型链路全程可见</strong><small>过程透明，结果可解释</small></span>
            </li>
            <li>
              <span class="story-feature-icon story-feature-security" aria-hidden="true"><ShieldCheckIcon /></span>
              <span><strong>业务边界、审批节点与运行记录</strong><small>合规可控，安全可靠</small></span>
            </li>
          </ul>
        </div>

        <p class="story-footnote">AI SERVICE HUB · 受控访问</p>
      </div>
    </section>

    <section ref="panelElement" class="login-panel" :style="{ '--card-scale': cardScale, '--card-top': `${cardTop}px` }">
      <p class="handwritten">AI，让好的服务发生</p>
      <form ref="cardElement" class="login-card" @submit.prevent="login">
        <header class="card-intro">
          <h2>登录智能服务工作台</h2>
          <p>使用组织账号继续访问业务数据和知识内容。</p>
        </header>

        <div class="form-field account-field">
          <label for="login-username">账号</label>
          <div class="input-shell">
            <UserIcon aria-hidden="true" />
            <input id="login-username" v-model.trim="form.username" name="username" autocomplete="username" required placeholder="请输入账号" />
          </div>
        </div>
        <div class="form-field password-field">
          <label for="login-password">密码</label>
          <div class="input-shell">
            <LockClosedIcon aria-hidden="true" />
            <input id="login-password" v-model="form.password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required placeholder="请输入密码" />
            <button class="password-visibility" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword">
              <EyeSlashIcon v-if="showPassword" aria-hidden="true" />
              <EyeIcon v-else aria-hidden="true" />
            </button>
          </div>
        </div>

        <div class="form-options">
          <label class="remember-option"><input v-model="rememberMe" type="checkbox" /><span>记住我</span></label>
          <button class="forgot-link" type="button" @click="showPasswordHelp">忘记密码?</button>
        </div>
        <p class="error" role="alert"><ExclamationCircleIcon v-if="error" aria-hidden="true" />{{ error }}</p>
        <button class="submit" type="submit" :disabled="submitting">
          <span>{{ submitting ? '正在验证…' : '登录工作台' }}</span><ArrowRightIcon v-if="!submitting" aria-hidden="true" />
        </button>

        <aside class="demo-account" aria-label="默认演示账号">
          <div class="demo-heading"><BookOpenIcon aria-hidden="true" /><strong>默认演示账号</strong></div>
          <p>账号：<span>admin</span><span class="demo-password">密码：&nbsp; Admin@123456</span></p>
          <small>首次部署后建议通过环境变量修改默认密码。</small>
          <DocumentTextIcon class="demo-watermark" aria-hidden="true" />
        </aside>
        <div class="trust-points" aria-label="平台保障">
          <span><ShieldCheckIcon aria-hidden="true" />安全访问</span>
          <span><DocumentTextIcon aria-hidden="true" />审计留痕</span>
          <span><UsersIcon aria-hidden="true" />权限可控</span>
        </div>
      </form>
    </section>

    <dialog ref="forgotDialog" class="password-help" aria-labelledby="password-help-title">
      <h2 id="password-help-title">忘记密码?</h2>
      <p>请联系组织管理员重置账号密码。默认演示账号的初始密码可在登录页查看。</p>
      <form method="dialog"><button type="submit">知道了</button></form>
    </dialog>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRightIcon, BookOpenIcon, DocumentTextIcon, EyeIcon, EyeSlashIcon,
  ExclamationCircleIcon, LockClosedIcon, ShieldCheckIcon, UserIcon, UsersIcon,
  ChatBubbleLeftEllipsisIcon, CircleStackIcon, ShareIcon, ChartBarIcon
} from '@heroicons/vue/24/outline'
import { authAPI } from '../services/api'
import { setAuthenticatedUser } from '../services/auth'
import { useLoginViewportFit } from '../composables/useLoginViewportFit'

const route = useRoute()
const router = useRouter()
const rememberMe = ref(localStorage.getItem('login-remember-me') !== 'false')
const form = ref({
  username: localStorage.getItem('login-remembered-username') || 'admin',
  password: 'Admin@123456'
})
const submitting = ref(false)
const showPassword = ref(false)
const error = ref('')
const forgotDialog = ref(null)
const { panelElement, cardElement, cardScale, cardTop } = useLoginViewportFit()

function showPasswordHelp() { forgotDialog.value?.showModal() }

async function login() {
  submitting.value = true
  error.value = ''
  try {
    const user = await authAPI.login({ ...form.value, rememberMe: rememberMe.value })
    if (rememberMe.value) localStorage.setItem('login-remembered-username', form.value.username)
    else localStorage.removeItem('login-remembered-username')
    localStorage.setItem('login-remember-me', String(rememberMe.value))
    setAuthenticatedUser(user)
    await router.replace(String(route.query.redirect || '/'))
  } catch (exception) {
    error.value = exception.message || '登录失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<style>
html:has(.login-page), body:has(.login-page) { overflow: hidden; scrollbar-width: none; }
html:has(.login-page)::-webkit-scrollbar, body:has(.login-page)::-webkit-scrollbar { display: none; }
</style>

<style scoped lang="scss">
.login-page {
  height: 100svh; width: 100%; overflow: hidden;
  display: grid;
  grid-template-columns: 57.177% minmax(0, 1fr);
  color: #172344;
  font-family: "HarmonyOS Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
}
.login-story {
  position: relative; height: 100svh; min-height: 0; overflow: hidden; isolation: isolate;
  background: #0b1b4e;
}
.story-art {
  position: absolute; z-index: 0; top: 50%; left: 50%; width: max(100%, 104.6493svh);
  aspect-ratio: 1283 / 1226; transform: translate(-50%, -50%);
  background: url('../assets/login-scene.png') center / 100% 100% no-repeat;
  container-type: inline-size;
}
.story-scene { position: absolute; z-index: 1; inset: 0; container-type: inline-size; }
.story-header { position: absolute; z-index: 4; top: 4.25%; left: 7.05%; right: 3.7%; display: flex; align-items: flex-start; justify-content: space-between; }
.story-brand { display: flex; align-items: center; gap: 1.85cqw; color: #fff; white-space: nowrap; }
.story-brand-icon {
  position: relative; width: 5.3cqw; height: 5.3cqw; display: grid; place-items: center;
  border-radius: 1.35cqw; background: linear-gradient(145deg, #5c96ff, #3467ef 64%, #6177ef);
  box-shadow: 0 .6cqw 2cqw rgba(30,91,244,.25), inset 0 .1cqw .1cqw rgba(255,255,255,.22);
}
.story-brand-icon svg { width: 2.85cqw; height: 2.85cqw; stroke-width: 2.1; }
.story-brand-icon i { position: absolute; right: .9cqw; bottom: 1.1cqw; width: .5cqw; height: .5cqw; border-radius: 50%; background: #72f6ec; }
.story-brand strong { font-size: 2.14cqw; font-weight: 800; letter-spacing: .025em; }
.story-motto { position: relative; margin-top: .65cqw; color: #a4b5d5; font-size: 1.2cqw; letter-spacing: .6cqw; white-space: nowrap; }
.story-motto span { display: block; width: 6.8cqw; height: .16cqw; margin: 1.3cqw 0 0 auto; background: linear-gradient(90deg, #a7baff 0 48%, #6278ed 48%); }
.story-copy { position: absolute; z-index: 3; top: 20%; left: 7.85%; width: 89%; color: #fff; }
.story-eyebrow { color: #acbce6; font-size: 1.55cqw; font-weight: 500; letter-spacing: .47cqw; line-height: 1.2; }
.story-copy h1 { margin: 1.5cqw 0 0; color: #fff; font-size: 5.9cqw; font-weight: 800; letter-spacing: .012em; line-height: 1.3; white-space: nowrap; text-shadow: 0 .5cqw 2cqw rgba(4,10,32,.15); }
.story-copy h1 span { background: linear-gradient(100deg, #9a9aff 4%, #6eaaff 44%, #83e7f0 100%); -webkit-background-clip: text; background-clip: text; color: transparent; }
.story-description { margin: 2.8cqw 0 0; color: #c3d0e9; font-size: 2.05cqw; line-height: 1.5; white-space: nowrap; }
.story-features { display: grid; gap: 2.45cqw; margin: 4.05cqw 0 0; padding: 0; list-style: none; }
.story-features li { display: flex; align-items: center; gap: 2.65cqw; min-height: 6.35cqw; }
.story-feature-icon { width: 6.25cqw; height: 6.25cqw; display: grid; flex: none; place-items: center; border: .1cqw solid rgba(160,190,255,.22); border-radius: 1.65cqw; }
.story-feature-icon svg { width: 3.6cqw; height: 3.6cqw; stroke-width: 2; }
.story-feature-knowledge { color: #80aaff; background: linear-gradient(145deg, #2e4c9f, #243767); }
.story-feature-tools { color: #b895ff; background: linear-gradient(145deg, #49459b, #2b356b); }
.story-feature-security { color: #6ce5e8; background: linear-gradient(145deg, #23647e, #1d4266); }
.story-features strong, .story-features small { display: block; white-space: nowrap; }
.story-features strong { font-size: 1.95cqw; font-weight: 700; letter-spacing: .01em; line-height: 1.4; }
.story-features small { margin-top: .36cqw; color: #aabbd8; font-size: 1.65cqw; line-height: 1.3; }
.story-robot-mark { position: absolute; top: 67%; left: 70.5%; color: #3f96e5; font-size: 3.2cqw; font-weight: 800; letter-spacing: .1em; transform: rotate(9deg); text-shadow: 0 .1cqw .3cqw rgba(65,122,244,.25); }
.story-bubble {
  position: absolute; z-index: 5; display: block; padding: 1.05cqw 1.7cqw; color: #fff;
  border: .12cqw solid rgba(170,216,255,.84); border-radius: 1.75cqw;
  background: linear-gradient(130deg, rgba(64,130,218,.77), rgba(79,72,178,.67));
  box-shadow: inset 0 0 1.1cqw rgba(178,229,255,.38), 0 0 1.4cqw rgba(77,133,254,.75);
  font-size: 1.95cqw; font-weight: 700; line-height: 1.35; white-space: nowrap;
}
.story-bubble::after { content: ''; position: absolute; left: 17%; bottom: -1cqw; width: 1.6cqw; height: 1.6cqw; border-left: .12cqw solid rgba(170,216,255,.84); border-bottom: .12cqw solid rgba(170,216,255,.84); background: #455db7; clip-path: polygon(0 0, 100% 0, 0 100%); transform: skewY(-10deg); }
.story-bubble-efficient { top: 35.4%; left: 75.6%; transform: rotate(-2deg); }
.story-bubble-smart { top: 43%; left: 83%; }
.story-bubble-create { top: 63.8%; left: 82.8%; padding: 1.45cqw 2.1cqw; line-height: 1.5; }
.story-bubble-create span { color: #e5a5fb; font-size: 2.6cqw; vertical-align: -.15cqw; }
.story-data-icon { position: absolute; width: 6.2cqw; height: 6.2cqw; color: #91c5ff; stroke-width: 2.4; filter: drop-shadow(0 0 .6cqw rgba(122,193,255,.9)); transform: translate(-50%, -50%); }
.story-data-list { left: 63.6%; top: 79.9%; }
.story-data-chart { left: 75.9%; top: 81.6%; }
.story-data-chat { left: 87.7%; top: 82.9%; }
.story-platform-copy { position: absolute; top: 89%; left: 60.7%; color: #92d5ff; font-family: Consolas, monospace; font-size: 1.5cqw; letter-spacing: .07em; white-space: nowrap; transform: rotate(8deg); text-shadow: 0 0 .5cqw rgba(103,202,255,.65); }
.story-footnote { position: absolute; z-index: 4; left: 7.85%; bottom: 4.5%; color: #a9b9d6; font-size: 1.5cqw; letter-spacing: .14cqw; }
@media (min-aspect-ratio: 2 / 1) and (min-width: 1181px) {
  .story-art { top: auto; bottom: 0; transform: translateX(-50%); }
  .story-eyebrow { font-size: 1.55svh; letter-spacing: .47svh; }
  .story-copy h1 { margin-top: 1.5svh; font-size: 5.9svh; }
  .story-description { margin-top: 2.8svh; font-size: 2.05svh; }
  .story-features { gap: 2.45svh; margin-top: 4.05svh; }
  .story-features li { gap: 2.65svh; min-height: 6.35svh; }
  .story-feature-icon { width: 6.25svh; height: 6.25svh; border-radius: 1.65svh; }
  .story-feature-icon svg { width: 3.6svh; height: 3.6svh; }
  .story-features strong { font-size: 1.95svh; }
  .story-features small { margin-top: .36svh; font-size: 1.65svh; }
  .story-platform-copy { top: 87.5%; }
}
.login-panel {
  position: relative; min-width: 0; height: 100%; overflow: hidden;
  background: radial-gradient(ellipse 70% 43% at 95% 25%, rgba(213,230,255,.76), transparent 70%),
    radial-gradient(ellipse 90% 48% at 28% 88%, rgba(232,238,255,.78), transparent 76%), #f8faff;
}
.handwritten {
  position: absolute; top: 32px; right: clamp(30px, 3.5vw, 60px); color: #56648e;
  font-family: "STKaiti", "KaiTi", cursive; font-size: 21px; font-style: italic;
  letter-spacing: 1px; line-height: 1.3; transform: rotate(-8deg); white-space: nowrap;
}
.handwritten::after {
  content: ""; position: absolute; left: 15px; right: -8px; bottom: -13px; height: 14px;
  border-top: 2px solid #6e82ff; border-radius: 50% 50% 0 0; transform: rotate(-2deg);
}
.login-card {
  position: absolute; top: var(--card-top, 108px); left: calc(50% + 4px);
  width: min(610px, calc(100% - 48px)); padding: 40px 48px 38px;
  transform: translateX(-50%) scale(var(--card-scale, 1)); transform-origin: top center;
  border: 1px solid rgba(255,255,255,.96); border-radius: 21px;
  background: linear-gradient(135deg, rgba(255,255,255,.94), rgba(255,255,255,.82));
  box-shadow: 0 24px 70px rgba(63,100,185,.065);
}
.card-intro h2 { margin: 0; color: #172344; font-size: 38px; font-weight: 800; letter-spacing: -.015em; line-height: 46px; white-space: nowrap; }
.card-intro p { margin-top: 8px; color: #5b6882; font-size: 19px; line-height: 28px; white-space: nowrap; }
.form-field label { display: block; color: #172344; font-size: 18px; font-weight: 700; line-height: 25px; }
.account-field { margin-top: 35px; }
.password-field { margin-top: 19px; }
.input-shell {
  height: 55px; display: flex; align-items: center; gap: 14px; margin-top: 7px; padding: 0 18px;
  border: 1px solid #cfd9e9; border-radius: 11px; background: #fff;
  box-shadow: 0 2px 4px rgba(73,94,138,.025); transition: border-color .18s ease, box-shadow .18s ease;
}
.input-shell:focus-within { border-color: #4a79f6; box-shadow: 0 0 0 4px rgba(67,115,246,.11); }
.input-shell > svg, .password-visibility svg { width: 23px; height: 23px; flex: none; color: #7b8ba9; stroke-width: 1.8; }
.input-shell input { width: 100%; min-width: 0; height: 100%; padding: 0; color: #1d2532; border: 0; outline: 0; background: transparent; font-size: 18px; font-weight: 500; }
.input-shell input::placeholder { color: #9aa6ba; }
.password-visibility { width: 25px; height: 28px; display: grid; flex: none; place-items: center; padding: 0; border: 0; background: none; }
.password-visibility:hover svg { color: #316ef6; }
.password-visibility:focus-visible { outline: 2px solid #316ef6; outline-offset: 3px; border-radius: 4px; }
.form-options { min-height: 26px; display: flex; align-items: center; justify-content: space-between; margin-top: 14px; }
.remember-option { display: inline-flex; align-items: center; gap: 11px; color: #40506c; font-size: 17px; cursor: pointer; }
.remember-option input { width: 23px; height: 23px; margin: 0; accent-color: #3777f6; cursor: pointer; }
.forgot-link { padding: 2px 0; color: #1587e9; border: 0; background: none; font-size: 16px; font-weight: 700; }
.forgot-link:hover { color: #1764d7; text-decoration: underline; }
.forgot-link:focus-visible { outline: 2px solid #316ef6; outline-offset: 4px; border-radius: 2px; }
.error { min-height: 20px; display: flex; align-items: center; gap: 7px; margin-top: 11px; color: #c2412d; font-size: 14px; line-height: 1.4; }
.error svg { width: 18px; height: 18px; flex: none; }
.submit {
  width: 100%; height: 61px; display: flex; align-items: center; justify-content: center; gap: 11px;
  margin-top: 27px; color: #fff; border: 0; border-radius: 11px;
  background: linear-gradient(100deg, #3b7ffd 0%, #315ffc 65%, #329dfd 100%);
  box-shadow: 0 12px 23px rgba(48,112,245,.16); font-size: 20px; font-weight: 800;
  letter-spacing: .025em; transition: filter .18s ease, transform .18s ease, box-shadow .18s ease;
}
.submit:hover { filter: brightness(1.045); box-shadow: 0 15px 28px rgba(48,112,245,.22); }
.submit:active { transform: translateY(1px); }
.submit:focus-visible { outline: 3px solid #80a5ff; outline-offset: 3px; }
.submit:disabled { opacity: .7; cursor: wait; }
.submit svg { width: 25px; height: 25px; stroke-width: 1.8; }
.demo-account {
  height: 123px; position: relative; overflow: hidden; margin-top: 24px; padding: 13px 19px;
  color: #586781; border: 1px solid #d4e0ff; border-radius: 12px;
  background: linear-gradient(105deg, #f1f5ff, #e8effe);
}
.demo-heading { display: flex; align-items: center; gap: 10px; color: #2b5cf2; font-size: 18px; line-height: 26px; }
.demo-heading svg { width: 22px; height: 22px; stroke-width: 2; }
.demo-heading strong { font-weight: 800; }
.demo-account p { position: relative; z-index: 1; margin-top: 8px; font-size: 17px; line-height: 25px; white-space: nowrap; }
.demo-password { margin-left: 22px; }
.demo-account small { position: relative; z-index: 1; display: block; margin-top: 7px; font-size: 14px; line-height: 20px; white-space: nowrap; }
.demo-watermark { position: absolute; right: 10px; bottom: 9px; width: 69px; height: 69px; color: #cfddff; transform: rotate(18deg); stroke-width: 1.2; }
.trust-points { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 34px; color: #64728e; }
.trust-points span { min-height: 32px; display: flex; align-items: center; justify-content: center; gap: 11px; font-size: 15px; font-weight: 600; white-space: nowrap; }
.trust-points span + span { border-left: 1px solid #d3dced; }
.trust-points svg { width: 25px; height: 25px; stroke-width: 1.8; }
.password-help { width: min(400px, calc(100vw - 36px)); padding: 28px; color: #172344; border: 1px solid #d9e4fa; border-radius: 16px; box-shadow: 0 22px 70px rgba(22,42,89,.2); font-family: inherit; }
.password-help::backdrop { background: rgba(19,33,70,.38); backdrop-filter: blur(3px); }
.password-help h2 { font-size: 22px; font-weight: 750; }
.password-help p { margin: 12px 0 22px; color: #5b6882; line-height: 1.7; }
.password-help form { display: flex; justify-content: flex-end; }
.password-help button { padding: 9px 22px; color: #fff; border: 0; border-radius: 8px; background: #326ef1; font-weight: 700; }
@media (max-width: 1320px) {
  .login-card { padding-right: 35px; padding-left: 35px; }
  .card-intro h2 { font-size: 35px; }
  .card-intro p { font-size: 17px; }
  .demo-account p { font-size: 15px; }
  .demo-password { margin-left: 12px; }
}
@media (max-width: 1180px) {
  .login-page { display: block; }
  .login-story { display: none; }
  .login-panel { height: 100%; }
  .login-card { padding-right: 48px; padding-left: 48px; }
  .card-intro h2 { font-size: 38px; }
  .card-intro p { font-size: 19px; }
  .demo-account p { font-size: 17px; }
  .demo-password { margin-left: 22px; }
}
@media (min-width: 641px) and (max-height: 850px) {
  .handwritten { top: 6px; font-size: 16px; }
  .handwritten::after { bottom: -8px; }
}
@media (max-width: 640px) {
  .login-panel { height: 100%; }
  .handwritten { top: 23px; right: 22px; font-size: 17px; }
  .handwritten::after { bottom: -10px; }
  .login-card { left: 50%; width: calc(100% - 32px); min-height: 0; padding: 28px 23px 25px; border-radius: 18px; }
  .card-intro h2 { font-size: clamp(25px, 7vw, 34px); line-height: 1.26; white-space: normal; }
  .card-intro p { margin-top: 9px; font-size: 15px; line-height: 1.6; white-space: normal; }
  .account-field { margin-top: 27px; }
  .form-field label { font-size: 16px; }
  .input-shell { height: 52px; }
  .input-shell input { font-size: 16px; }
  .remember-option { font-size: 15px; }
  .forgot-link { font-size: 14px; }
  .submit { height: 54px; font-size: 18px; }
  .demo-account { height: auto; min-height: 123px; padding: 13px 14px; }
  .demo-heading { font-size: 16px; }
  .demo-account p { font-size: 14px; line-height: 1.5; white-space: normal; }
  .demo-account small { font-size: 12px; line-height: 1.5; white-space: normal; }
  .demo-password { display: block; margin-left: 0; }
  .trust-points { margin-top: 27px; }
  .trust-points span { flex-direction: column; gap: 4px; font-size: 11px; }
  .trust-points svg { width: 21px; height: 21px; }
}
@media (max-width: 640px) and (max-height: 750px) {
  .handwritten { top: 8px; font-size: 14px; }
  .handwritten::after { bottom: -7px; }
}
@media (prefers-reduced-motion: reduce) { .input-shell, .submit { transition: none; } }
</style>
