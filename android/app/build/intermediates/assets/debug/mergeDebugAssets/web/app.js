'use strict';

// ── 상태 ────────────────────────────────────────────────────────
let ws = null;
let reconnectDelay = 1000;
let notifications = new Map(); // id → payload
let activeReply = null;        // 현재 답장 중인 알림 payload

// ── WebSocket 연결 ────────────────────────────────────────────────
function connect() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = localStorage.getItem('reverb_token');
  const url = token
    ? `${protocol}//${location.host}/ws?token=${encodeURIComponent(token)}`
    : `${protocol}//${location.host}/ws`;

  ws = new WebSocket(url);

  ws.onopen = () => {
    reconnectDelay = 1000;
    setOnline(true);
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
    setOnline(false);
    if (event.code === 1008) {
      // 토큰 오류: 재입력 요청
      localStorage.removeItem('reverb_token');
      const newToken = prompt('Reverb 토큰을 입력하세요 (Android 앱에서 확인):');
      if (newToken) {
        localStorage.setItem('reverb_token', newToken.trim());
        setTimeout(connect, 500);
      }
      return;
    }
    scheduleReconnect();
  };

  ws.onerror = () => {
    setOnline(false);
  };
}

function scheduleReconnect() {
  setTimeout(() => {
    if (!ws || ws.readyState === WebSocket.CLOSED) connect();
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
  }
}

function onSnapshot(data) {
  // 상태 업데이트
  document.getElementById('device-name').textContent = data.deviceName || 'Android';
  updateBattery(data.batteryLevel, data.batteryCharging);

  // 알림 전체 렌더링 (최신순)
  notifications.clear();
  const feed = document.getElementById('notifications');
  feed.innerHTML = '';

  if (!data.notifications || data.notifications.length === 0) {
    feed.innerHTML = `
      <div id="empty-state">
        <p>📵</p>
        <p>아직 알림이 없습니다</p>
        <p class="hint">Android 폰에서 알림이 오면 여기에 표시됩니다</p>
      </div>`;
    return;
  }

  // 오래된 것 → 최신 순으로 정렬 후 역순 렌더 (최신이 맨 위)
  const sorted = [...data.notifications].sort((a, b) => a.timestamp - b.timestamp);
  sorted.reverse().forEach(n => {
    notifications.set(n.id, n);
    feed.appendChild(buildCard(n));
  });
}

function onNotification(data) {
  // empty-state 제거
  const emptyState = document.getElementById('empty-state');
  if (emptyState) emptyState.remove();

  notifications.set(data.id, data);

  const feed = document.getElementById('notifications');
  const card = buildCard(data);
  feed.prepend(card);

  // 최대 200개 유지
  while (feed.children.length > 200) feed.lastChild.remove();

  // 오디오 큐 (탭이 포커스된 경우에만)
  if (document.visibilityState === 'visible') playChime();
}

function onStatus(data) {
  if (data.deviceName) document.getElementById('device-name').textContent = data.deviceName;
  updateBattery(data.batteryLevel, data.batteryCharging);
}

// ── 카드 빌더 ────────────────────────────────────────────────────
function buildCard(n) {
  const card = document.createElement('div');
  card.className = `notification-card ${n.category || 'generic'}`;
  card.dataset.id = n.id;

  const time = formatTime(n.timestamp);
  const hasReply = n.conversationId && n.actions && n.actions.some(a =>
    /답장|reply|respond/i.test(a)
  );

  card.innerHTML = `
    <div class="card-header">
      <span class="card-app-label">${escHtml(n.appLabel || n.packageName)}</span>
      <span class="card-time">${time}</span>
    </div>
    ${n.title ? `<div class="card-title">${escHtml(n.title)}</div>` : ''}
    ${n.body  ? `<div class="card-body">${escHtml(n.body)}</div>` : ''}
    ${hasReply ? `
    <div class="card-actions">
      <button class="btn-reply" onclick="openReplyModal(${JSON.stringify(n).replace(/"/g, '&quot;')})">
        답장
      </button>
    </div>` : ''}
  `;
  return card;
}

// ── 답장 모달 ────────────────────────────────────────────────────
function openReplyModal(payload) {
  activeReply = typeof payload === 'string' ? JSON.parse(payload) : payload;
  document.getElementById('modal-sender').textContent = activeReply.title || activeReply.conversationId || '알 수 없음';
  document.getElementById('modal-app').textContent = activeReply.appLabel || '';
  document.getElementById('reply-textarea').value = '';
  document.getElementById('btn-send').disabled = false;
  document.getElementById('reply-modal').showModal();
  document.getElementById('reply-textarea').focus();
}

function closeReplyModal() {
  document.getElementById('reply-modal').close();
  activeReply = null;
}

async function sendReply() {
  if (!activeReply) return;
  const body = document.getElementById('reply-textarea').value.trim();
  if (!body) return;

  const btn = document.getElementById('btn-send');
  btn.disabled = true;
  btn.textContent = '전송 중...';

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
      closeReplyModal();
    } else {
      const err = await res.json().catch(() => ({}));
      showToast('전송 실패: ' + (err.error || res.status));
      btn.disabled = false;
      btn.textContent = '보내기';
    }
  } catch (e) {
    showToast('네트워크 오류');
    btn.disabled = false;
    btn.textContent = '보내기';
  }
}

// Enter 키로 전송 (Shift+Enter = 줄바꿈)
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && document.getElementById('reply-modal').open) {
    e.preventDefault();
    sendReply();
  }
  if (e.key === 'Escape' && document.getElementById('reply-modal').open) {
    closeReplyModal();
  }
});

// ── 필터 패널 ────────────────────────────────────────────────────
function toggleFilter() {
  const panel = document.getElementById('filter-panel');
  panel.classList.toggle('hidden');
  if (!panel.classList.contains('hidden')) loadFilters();
}

async function loadFilters() {
  try {
    const res = await fetch('/api/filters');
    const config = await res.json();

    // 모드 라디오 버튼
    const radios = document.querySelectorAll('input[name="mode"]');
    radios.forEach(r => { r.checked = r.value === config.mode; });

    // 패키지 목록
    const container = document.getElementById('filter-packages');
    container.innerHTML = '';
    (config.packages || []).forEach(pkg => {
      const item = document.createElement('div');
      item.className = 'filter-pkg-item';
      item.innerHTML = `
        <span>${escHtml(pkg)}</span>
        <button onclick="removeFilter('${escHtml(pkg)}')" style="background:none;border:none;color:#ff3b30;cursor:pointer;font-size:18px;min-width:44px;min-height:44px;">✕</button>
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
  dot.className = 'dot ' + (online ? 'online' : 'offline');
  if (!online) {
    document.getElementById('device-name').textContent = '연결 끊김, 재연결 중...';
  }
}

function updateBattery(level, charging) {
  const el = document.getElementById('battery-text');
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
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function showToast(msg, duration = 2500) {
  const toast = document.getElementById('toast');
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
  } catch (_) {}
}

// ── 초기화 ───────────────────────────────────────────────────────
connect();
