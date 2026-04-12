'use strict';

// ── 상태 ────────────────────────────────────────────────────────
let ws = null;
let reconnectDelay = 1000;
let notifications = new Map(); // id → payload
let activeReply = null;        // 현재 답장 중인 알림 payload
let activeDetailCard = null;   // 액티브(선택된) 카드 엘리먼트
let connectionHealthy = false; // 연결 건강 상태

// ── WebSocket 연결 ────────────────────────────────────────────────
function connect() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = localStorage.getItem('reverb_token');
  const url = token
    ? `${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`
    : `${protocol}//${location.host}/ws`;

  console.log('WebSocket 연결 시도:', url);
  ws = new WebSocket(url);

  ws.onopen = () => {
    console.log('✅ WebSocket 연결 성공');
    reconnectDelay = 1000;
    connectionHealthy = true;
    setOnline(true);
    showToast('🟢 서버에 연결되었습니다', 2000);
  };

  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      handleMessage(data);
    } catch (e) {
      console.error('메시지 파싱 오류:', e);
    }
  };

  ws.onclose = (event) => {
    console.log('⚠️ WebSocket 연결 종료 (코드:', event.code, ' reason:', event.reason, ')');
    connectionHealthy = false;
    setOnline(false);
    if (event.code === 1008) {
      // 토큰 오류: 재입력 요청
      console.warn('토큰 인증 실패');
      localStorage.removeItem('reverb_token');
      const newToken = prompt('Reverb 토큰을 입력하세요 (Android 앱에서 확인):');
      if (newToken) {
        localStorage.setItem('reverb_token', newToken.trim());
        setTimeout(connect, 500);
      }
      return;
    }
    showToast('🔴 연결 끊김, 재연결 중...', 3000);
    scheduleReconnect();
  };

  ws.onerror = (error) => {
    console.error('❌ WebSocket 오류:', error);
    connectionHealthy = false;
    setOnline(false);
  };
}

function scheduleReconnect() {
  console.log(`🔄 ${reconnectDelay}ms 후 재연결 시도...`);
  setTimeout(() => {
    if (!ws || ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING) {
      connect();
    }
  }, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, 30000);
}

// ── 메시지 처리 ────────────────────────────────────────────────
function handleMessage(data) {
  switch (data.type) {
    case 'snapshot':
      onSnapshot(data);
      break;
    case 'notification':
      onNotification(data);
      break;
    case 'status':
      onStatus(data);
      break;
    default:
      console.log('알 수 없는 메시지 타입:', data.type, data);
  }
}

function onSnapshot(data) {
  console.log('📦 스냅샷 수신 - 알림 개수:', data.notifications?.length || 0);

  // 상태 업데이트
  document.getElementById('device-name').textContent = data.deviceName || 'Android';
  updateBattery(data.batteryLevel, data.batteryCharging);

  // 알림 전체 렌더링 (최신순)
  notifications.clear();
  const feed = document.getElementById('notifications');
  feed.innerHTML = '';

  if (!data.notifications || data.notifications.length === 0) {
    feed.innerHTML = `
      <div id="empty-state" class="flex flex-col items-center justify-center h-full text-center text-on-surface-variant opacity-60 gap-4 mt-20">
          <span class="material-symbols-outlined text-6xl opacity-40">phonelink_ring</span>
          <p class="text-lg font-bold">아직 알림이 없습니다</p>
          <p class="text-xs uppercase tracking-widest mt-2">Android 폰에서 알림이 오면 표시됩니다</p>
      </div>`;
    return;
  }

  // 오래된 것 → 최신 순으로 정렬 후 역순 렌더 (최신이 맨 위)
  const sorted = [...data.notifications].sort((a, b) => a.timestamp - b.timestamp);
  sorted.reverse().forEach(n => {
    notifications.set(n.id, n);
    feed.appendChild(buildCard(n));
  });

  console.log('✅ 스냅샷 렌더링 완료 -', sorted.length, '개 알림 표시');
}

function onNotification(data) {
  console.log('📨 새 알림 수신:', data.appLabel, '-', data.title);

  // 중복 확인
  if (notifications.has(data.id)) {
    console.log('⚠️ 중복 알림 무시:', data.id);
    return;
  }

  // empty-state 제거
  const emptyState = document.getElementById('empty-state');
  if (emptyState) emptyState.remove();

  notifications.set(data.id, data);

  const feed = document.getElementById('notifications');
  const card = buildCard(data);

  // 애니메이션 효과 추가
  card.style.opacity = '0';
  card.style.transform = 'translateY(-20px)';
  feed.prepend(card);

  // 부드러운 애니메이션
  requestAnimationFrame(() => {
    card.style.transition = 'opacity 0.3s ease, transform 0.3s ease';
    card.style.opacity = '1';
    card.style.transform = 'translateY(0)';
  });

  // 최대 200개 유지
  while (feed.children.length > 200) feed.lastChild.remove();

  // 토스트 알림 (새 알림 왔음을 명시적으로 표시)
  showToast(`🔔 ${data.appLabel || '새 알림'}`, 2000);

  // 오디오 큐 (탭이 포커스된 경우에만)
  if (document.visibilityState === 'visible') playChime();
}

function onStatus(data) {
  if (data.deviceName) document.getElementById('device-name').textContent = data.deviceName;
  updateBattery(data.batteryLevel, data.batteryCharging);
}

// ── 카드 빌더 (Stitch UI 구조 연동) ────────────────────────────────────────────────────
function buildCard(n) {
  const card = document.createElement('div');
  // 카드 스타일: overflow-hidden 제거하여 패딩 정상 작동
  card.className = `glass-pane p-5 rounded-2xl border border-white/5 cursor-pointer transition-all hover:bg-white/5 mb-3`;
  card.dataset.id = n.id;

  const time = formatTime(n.timestamp);

  card.innerHTML = `
    <div class="flex items-start gap-4 pointer-events-none">
        <div class="w-12 h-12 rounded-xl bg-primary-container/20 flex items-center justify-center flex-shrink-0 mt-0.5">
            <span class="material-symbols-outlined text-2xl text-primary-fixed" style="font-variation-settings: 'FILL' 1;">
              ${n.category === 'call' ? 'call' : (n.category === 'sms' ? 'chat' : 'notifications')}
            </span>
        </div>
        <div class="flex-1 min-w-0">
            <div class="flex justify-between items-start mb-1 gap-2">
                <h3 class="font-bold text-on-surface text-sm card-title">${escHtml(n.title || n.appLabel || n.packageName)}</h3>
                <span class="text-[10px] text-on-surface-variant font-medium uppercase tracking-widest flex-shrink-0">${time}</span>
            </div>
            <p class="text-xs text-on-surface-variant card-body">${escHtml(n.body || '')}</p>
        </div>
    </div>
  `;

  card.onclick = () => showDetailView(n, card);
  return card;
}

// ── 우측 디테일 창 보기 로직 ───────────────────────────────────────────────────
function showDetailView(n, cardEl) {
  activeReply = n;

  // 카드 하이라이트 토글
  if (activeDetailCard) {
    activeDetailCard.classList.remove('border-primary/40', 'bg-white/5');
    activeDetailCard.classList.add('border-white/5');
  }
  activeDetailCard = cardEl;
  cardEl.classList.remove('border-white/5');
  cardEl.classList.add('border-primary/40', 'bg-white/5');

  // 디테일 패널 전환
  const emptyPane = document.getElementById('right-pane-empty');
  const contentPane = document.getElementById('right-pane-content');

  if (emptyPane && contentPane) {
    emptyPane.classList.add('hidden');
    contentPane.classList.remove('hidden');
    contentPane.classList.add('flex');
  }

  // 텍스트 & 앱 정보 업데이트
  document.getElementById('detail-sender').textContent = n.title || n.appLabel || n.packageName;
  document.getElementById('detail-app').textContent = n.appLabel || '';
  document.getElementById('detail-text').textContent = n.body || '';
  document.getElementById('detail-time').textContent = formatTime(n.timestamp);

  // 답장 가능 여부에 따른 하단 입력 영역 토글
  const hasReply = n.conversationId && n.actions && n.actions.some(a => /답장|reply|respond/i.test(a));
  const replyArea = document.getElementById('reply-area');
  const replyInput = document.getElementById('reply-input');

  if (replyArea) {
    if (hasReply) {
      replyArea.classList.remove('hidden');
      replyArea.classList.add('flex');
      replyInput.value = '';
      replyInput.focus();
    } else {
      replyArea.classList.add('hidden');
      replyArea.classList.remove('flex');
    }
  }
}

// 기존 Dialog용은 남겨두지만 더 이상 사용하지 않으므로 무시해도 무방
function openReplyModal(payload) {
  activeReply = typeof payload === 'string' ? JSON.parse(payload) : payload;
}
function closeReplyModal() {
  activeReply = null;
}

// ── 답장 API 호출 ────────────────────────────────────────────────────────
async function sendReply() {
  if (!activeReply) return;
  const input = document.getElementById('reply-input');
  const body = input ? input.value.trim() : '';
  if (!body) return;

  const btn = document.getElementById('btn-send');
  if (btn) btn.disabled = true;

  try {
    const res = await fetch('/api/reply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: activeReply.conversationId,
        packageName: activeReply.packageName,
        replyBody: body
      })
    });
    if (res.ok) {
      showToast('답장이 전송되었습니다 ✓');
      if (input) input.value = '';
      if (btn) btn.disabled = false;
    } else {
      const err = await res.json().catch(() => ({}));
      showToast('전송 실패: ' + (err.error || res.status));
      if (btn) btn.disabled = false;
    }
  } catch (e) {
    showToast('네트워크 오류');
    if (btn) btn.disabled = false;
  }
}

// Enter 키 전송 로직 (새로운 Input 포커스 시)
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    const replyInput = document.getElementById('reply-input');
    if (document.activeElement === replyInput) {
      e.preventDefault();
      sendReply();
    }
  }
});

// ── 필터 패널 ────────────────────────────────────────────────────
function toggleFilter() {
  const panel = document.getElementById('filter-panel');
  if (!panel) return;
  panel.classList.toggle('hidden');
  if (!panel.classList.contains('hidden')) loadFilters();
}

async function loadFilters() {
  try {
    const res = await fetch('/api/filters');
    const config = await res.json();

    const radios = document.querySelectorAll('input[name="mode"]');
    radios.forEach(r => { r.checked = r.value === config.mode; });

    const container = document.getElementById('filter-packages');
    container.innerHTML = '';
    (config.packages || []).forEach(pkg => {
      const item = document.createElement('div');
      item.className = 'filter-pkg-item';
      item.innerHTML = `
        <span class="truncate pr-2">${escHtml(pkg)}</span>
        <button onclick="removeFilter('${escHtml(pkg)}')" class="text-red-400 hover:text-red-300">
          <span class="material-symbols-outlined text-lg">close</span>
        </button>
      `;
      container.appendChild(item);
    });
  } catch (e) {
    console.error('필터 로드 실패:', e);
  }
}

async function setFilterMode(mode) {
  try {
    const res = await fetch('/api/filters');
    const config = await res.json();
    await fetch('/api/filters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...config, mode })
    });
  } catch (e) {
    console.error('필터 모드 변경 실패:', e);
  }
}

async function removeFilter(pkg) {
  try {
    const res = await fetch('/api/filters');
    const config = await res.json();
    const packages = (config.packages || []).filter(p => p !== pkg);
    await fetch('/api/filters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...config, packages })
    });
    loadFilters();
  } catch (e) {
    console.error('필터 제거 실패:', e);
  }
}

// ── UI 헬퍼 ──────────────────────────────────────────────────────
function setOnline(online) {
  const dot = document.getElementById('connection-dot');
  if (dot) dot.className = 'dot ' + (online ? 'online' : 'offline');
  const dName = document.getElementById('device-name');
  if (!online && dName) {
    dName.textContent = '연결 끊김, 재연결 중...';
  }
}

function updateBattery(level, charging) {
  const el = document.getElementById('battery-text');
  if (!el) return;
  if (level == null || level < 0) { el.textContent = ''; return; }
  const icon = charging ? '⚡' : (level <= 20 ? '🪫' : '🔋');
  el.textContent = `${icon} ${level}%`;
}

function formatTime(ts) {
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  if (isToday) {
    return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' }) +
    ' ' + d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function escHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function showToast(msg, duration = 2500) {
  const toast = document.getElementById('toast');
  if (!toast) return;
  toast.textContent = msg;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), duration);
}

function playChime() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.frequency.value = 880;
    osc.type = 'sine';
    gain.gain.setValueAtTime(0.1, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.3);
    osc.start(ctx.currentTime);
    osc.stop(ctx.currentTime + 0.3);
  } catch (_) { }
}

// ── 초기화 ───────────────────────────────────────────────────────
connect();

// ── 테스트 알림 전송 ─────────────────────────────────────────────
async function sendTestNotification() {
  console.log('🧪 테스트 알림 전송 요청');

  // 서버로 테스트 알림 요청을 보냄
  try {
    const response = await fetch('/api/test-notification', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    });

    if (response.ok) {
      console.log('✅ 서버로 테스트 알림 요청 전송 성공');
      showToast('🔔 서버에 테스트 알림 요청을 보냈습니다', 2000);
    } else {
      console.warn('⚠️ 서버 응답 오류:', response.status);
      showToast('❌ 서버 요청 실패', 2000);
    }
  } catch (e) {
    console.error('❌ 네트워크 오류:', e);
    showToast('❌ 네트워크 오류: ' + e.message, 3000);
  }
}
