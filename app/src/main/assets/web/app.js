'use strict';

const state = {
  ws: null,
  reconnectDelay: 1000,
  notifications: [],
  selectedId: null,
  freshIds: new Set(),
  filters: {
    category: 'all'
  },
  forwarding: {
    mode: 'blacklist',
    packages: []
  }
};

const els = {
  deviceName: document.getElementById('device-name'),
  connectionSummary: document.getElementById('connection-summary'),
  connectionDot: document.getElementById('connection-dot'),
  connectionLabel: document.getElementById('connection-label'),
  batteryText: document.getElementById('battery-text'),
  categoryFilters: document.getElementById('category-filters'),
  resultsCount: document.getElementById('results-count'),
  notificationsList: document.getElementById('notifications-list'),
  emptyState: document.getElementById('empty-state'),
  detailPanel: document.getElementById('detail-panel'),
  detailEmpty: document.getElementById('detail-empty'),
  detailContent: document.getElementById('detail-content'),
  detailSender: document.getElementById('detail-sender'),
  detailApp: document.getElementById('detail-app'),
  detailTime: document.getElementById('detail-time'),
  detailCategory: document.getElementById('detail-category'),
  detailText: document.getElementById('detail-text'),
  replySection: document.getElementById('reply-section'),
  replyInput: document.getElementById('reply-input'),
  sendButton: document.getElementById('btn-send'),
  clearFeedButton: document.getElementById('btn-clear-feed'),
  openForwardingButton: document.getElementById('btn-open-forwarding'),
  closeForwardingButton: document.getElementById('btn-close-forwarding'),
  refreshForwardingButton: document.getElementById('btn-refresh-forwarding'),
  forwardingDrawer: document.getElementById('forwarding-drawer'),
  forwardingPackages: document.getElementById('forwarding-packages'),
  forwardingEmpty: document.getElementById('forwarding-empty'),
  testButton: document.getElementById('btn-test'),
  ringButton: document.getElementById('btn-ring'),
  closeDetailButton: document.getElementById('btn-close-detail'),
  overlay: document.getElementById('overlay'),
  toast: document.getElementById('toast')
};

function syncTokenFromQuery() {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token');
  if (!token) return;

  localStorage.setItem('reverb_token', token.trim());
  params.delete('token');
  const next = params.toString();
  const nextUrl = next ? `${window.location.pathname}?${next}` : window.location.pathname;
  window.history.replaceState({}, '', nextUrl);
}

function setConnectionState(isOnline, summary) {
  els.connectionDot.classList.toggle('online', isOnline);
  els.connectionDot.classList.toggle('offline', !isOnline);
  els.connectionLabel.textContent = isOnline ? '온라인' : '오프라인';
  els.connectionSummary.textContent = summary;
}

function connect() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const token = localStorage.getItem('reverb_token');
  const url = token
    ? `${protocol}//${window.location.host}/ws?token=${encodeURIComponent(token)}`
    : `${protocol}//${window.location.host}/ws`;

  state.ws = new WebSocket(url);

  state.ws.onopen = () => {
    state.reconnectDelay = 1000;
    setConnectionState(true, '');
    showToast('서버와 연결되었습니다.');
  };

  state.ws.onmessage = event => {
    try {
      handleMessage(JSON.parse(event.data));
    } catch (error) {
      showToast(`메시지를 해석하지 못했습니다: ${error.message}`);
    }
  };

  state.ws.onclose = event => {
    setConnectionState(false, '');

    if (event.code === 1008) {
      localStorage.removeItem('reverb_token');
      const nextToken = window.prompt('Reverb 토큰을 입력하세요. Android 앱 대시보드에서 확인할 수 있습니다.');
      if (nextToken) {
        localStorage.setItem('reverb_token', nextToken.trim());
        window.setTimeout(connect, 400);
      }
      return;
    }

    scheduleReconnect();
  };

  state.ws.onerror = () => {
    setConnectionState(false, '');
  };
}

function scheduleReconnect() {
  const delay = state.reconnectDelay;
  window.setTimeout(() => {
    if (!state.ws || state.ws.readyState >= WebSocket.CLOSING) {
      connect();
    }
  }, delay);
  state.reconnectDelay = Math.min(state.reconnectDelay * 2, 30000);
}

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
      showToast('알 수 없는 메시지 타입을 받았습니다.');
  }
}

function onSnapshot(data) {
  els.deviceName.textContent = data.deviceName || 'Android 기기';
  updateBattery(data.batteryLevel, data.batteryCharging);

  state.notifications = Array.isArray(data.notifications)
    ? [...data.notifications].sort((a, b) => b.timestamp - a.timestamp)
    : [];

  if (!state.selectedId && state.notifications.length > 0) {
    state.selectedId = state.notifications[0].id;
  }

  renderNotifications();
  renderDetail();
}

function onNotification(notification) {
  if (state.notifications.some(item => item.id === notification.id)) {
    return;
  }

  state.notifications.unshift(notification);
  state.notifications.sort((a, b) => b.timestamp - a.timestamp);
  state.freshIds.add(notification.id);
  state.selectedId = notification.id;

  renderNotifications();
  renderDetail();
  openDetailIfMobile();
  showToast(`${notification.appLabel || '새 알림'} 알림이 도착했습니다.`);

  window.setTimeout(() => {
    state.freshIds.delete(notification.id);
    renderNotifications();
  }, 5000);
}

function onStatus(data) {
  if (data.deviceName) {
    els.deviceName.textContent = data.deviceName;
  }
  updateBattery(data.batteryLevel, data.batteryCharging);
}

function updateBattery(level, charging) {
  if (typeof level !== 'number' || level < 0) {
    els.batteryText.textContent = '확인 중';
    return;
  }

  els.batteryText.textContent = charging ? `${level}% 충전` : `${level}%`;
}

function getFilteredNotifications() {
  return state.notifications.filter(notification => {
    return state.filters.category === 'all' || notification.category === state.filters.category;
  });
}

function renderNotifications() {
  const filtered = getFilteredNotifications();
  els.notificationsList.innerHTML = '';
  els.resultsCount.textContent = `${filtered.length}개 알림`;
  els.emptyState.hidden = filtered.length > 0;

  filtered.forEach(notification => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'notification-card';
    button.classList.toggle('is-selected', notification.id === state.selectedId);
    button.classList.toggle('is-fresh', state.freshIds.has(notification.id));
    button.setAttribute('aria-pressed', notification.id === state.selectedId ? 'true' : 'false');
    button.innerHTML = `
      <div class="notification-card__top">
        <div>
          <p class="notification-card__app">${escapeHtml(notification.appLabel || notification.packageName)}</p>
          <h3 class="notification-card__title">${escapeHtml(notification.title || notification.appLabel || '제목 없음')}</h3>
        </div>
        <span class="notification-card__time">${formatTime(notification.timestamp)}</span>
      </div>
      <p class="notification-card__body">${escapeHtml(notification.body || '본문이 없는 알림입니다.')}</p>
      <div class="notification-card__bottom">
        <span class="notification-card__category">${categoryLabel(notification.category)}</span>
        <span class="notification-card__category">${canReply(notification) ? '답장 가능' : '읽기 전용'}</span>
      </div>
    `;

    button.addEventListener('click', () => {
      state.selectedId = notification.id;
      renderNotifications();
      renderDetail();
      openDetailIfMobile();
    });

    els.notificationsList.appendChild(button);
  });
}

function renderDetail() {
  const notification = state.notifications.find(item => item.id === state.selectedId);
  const visibleIds = new Set(getFilteredNotifications().map(item => item.id));
  const isVisible = notification && visibleIds.has(notification.id);

  if (!notification || !isVisible) {
    els.detailEmpty.hidden = false;
    els.detailContent.hidden = true;
    closeDetailIfMobile();
    return;
  }

  els.detailEmpty.hidden = true;
  els.detailContent.hidden = false;
  els.detailSender.textContent = notification.title || notification.appLabel || notification.packageName;
  els.detailApp.textContent = notification.appLabel || notification.packageName;
  els.detailTime.textContent = formatFullTime(notification.timestamp);
  els.detailCategory.textContent = categoryLabel(notification.category);
  els.detailText.textContent = notification.body || '본문이 없는 알림입니다.';

  const replyable = canReply(notification);
  els.replySection.hidden = !replyable;
  if (!replyable) {
    els.replyInput.value = '';
  }
}

function canReply(notification) {
  return Boolean(
    notification.conversationId &&
    Array.isArray(notification.actions) &&
    notification.actions.some(action => /답장|reply|respond/i.test(action))
  );
}

function categoryLabel(category) {
  switch (category) {
    case 'sms':
      return '메시지';
    case 'call':
      return '전화';
    case 'media':
      return '미디어';
    default:
      return '기타';
  }
}

async function sendReply() {
  const notification = state.notifications.find(item => item.id === state.selectedId);
  if (!notification || !canReply(notification)) {
    return;
  }

  const replyBody = els.replyInput.value.trim();
  if (!replyBody) {
    showToast('답장 내용을 입력하세요.');
    return;
  }

  els.sendButton.disabled = true;

  try {
    const response = await fetch('/api/reply', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: notification.conversationId,
        packageName: notification.packageName,
        replyBody
      })
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.success === false) {
      throw new Error(payload.error || `HTTP ${response.status}`);
    }

    els.replyInput.value = '';
    showToast('답장을 전송했습니다.');
  } catch (error) {
    showToast(`답장 전송에 실패했습니다: ${error.message}`);
  } finally {
    els.sendButton.disabled = false;
  }
}

async function sendTestNotification() {
  try {
    const response = await fetch('/api/test-notification', { method: 'POST' });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    showToast('테스트 알림을 요청했습니다.');
  } catch (error) {
    showToast(`테스트 알림 요청에 실패했습니다: ${error.message}`);
  }
}

async function ringDevice() {
  try {
    const response = await fetch('/api/ring', { method: 'POST' });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    showToast('기기 벨소리를 재생했습니다.');
  } catch (error) {
    showToast(`폰 찾기 요청에 실패했습니다: ${error.message}`);
  }
}

async function loadForwardingRules() {
  try {
    const response = await fetch('/api/filters');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const config = await response.json();
    state.forwarding.mode = config.mode || 'blacklist';
    state.forwarding.packages = Array.isArray(config.packages) ? config.packages : [];
    renderForwardingRules();
  } catch (error) {
    showToast(`전달 규칙을 불러오지 못했습니다: ${error.message}`);
  }
}

function renderForwardingRules() {
  document.querySelectorAll('input[name="forwarding-mode"]').forEach(input => {
    input.checked = input.value === state.forwarding.mode;
  });

  els.forwardingPackages.innerHTML = '';
  els.forwardingEmpty.hidden = state.forwarding.packages.length > 0;

  state.forwarding.packages.forEach(packageName => {
    const item = document.createElement('li');
    item.innerHTML = `
      <div>
        <strong>${escapeHtml(packageName.split('.').pop() || packageName)}</strong>
        <br />
        <code>${escapeHtml(packageName)}</code>
      </div>
    `;

    const removeButton = document.createElement('button');
    removeButton.type = 'button';
    removeButton.className = 'secondary-button';
    removeButton.textContent = '제거';
    removeButton.addEventListener('click', () => removeForwardingPackage(packageName));
    item.appendChild(removeButton);
    els.forwardingPackages.appendChild(item);
  });
}

async function updateForwardingMode(mode) {
  try {
    const response = await fetch('/api/filters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mode,
        packages: state.forwarding.packages
      })
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    state.forwarding.mode = mode;
    renderForwardingRules();
    showToast('전달 모드를 변경했습니다.');
  } catch (error) {
    showToast(`전달 모드 변경에 실패했습니다: ${error.message}`);
  }
}

async function removeForwardingPackage(packageName) {
  const nextPackages = state.forwarding.packages.filter(item => item !== packageName);
  try {
    const response = await fetch('/api/filters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mode: state.forwarding.mode,
        packages: nextPackages
      })
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    state.forwarding.packages = nextPackages;
    renderForwardingRules();
    showToast('패키지를 전달 규칙에서 제거했습니다.');
  } catch (error) {
    showToast(`패키지 제거에 실패했습니다: ${error.message}`);
  }
}

function openForwardingDrawer() {
  els.forwardingDrawer.hidden = false;
  els.overlay.hidden = false;
}

function closeForwardingDrawer() {
  els.forwardingDrawer.hidden = true;
  if (!els.detailPanel.classList.contains('is-open')) {
    els.overlay.hidden = true;
  }
}

function openDetailIfMobile() {
  if (window.innerWidth > 840) {
    return;
  }
  els.detailPanel.classList.add('is-open');
  els.overlay.hidden = false;
}

function closeDetailIfMobile() {
  if (window.innerWidth > 840) {
    return;
  }
  els.detailPanel.classList.remove('is-open');
  if (els.forwardingDrawer.hidden) {
    els.overlay.hidden = true;
  }
}

function showToast(message) {
  els.toast.textContent = message;
  els.toast.classList.add('is-visible');
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    els.toast.classList.remove('is-visible');
  }, 2600);
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit'
  });
}

function formatFullTime(timestamp) {
  return new Date(timestamp).toLocaleString('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function bindEvents() {
  els.categoryFilters.addEventListener('click', event => {
    const button = event.target.closest('button[data-category]');
    if (!button) return;

    state.filters.category = button.dataset.category;
    els.categoryFilters.querySelectorAll('.chip').forEach(chip => {
      chip.classList.toggle('is-active', chip === button);
    });
    renderNotifications();
    renderDetail();
  });

  els.sendButton.addEventListener('click', sendReply);
  els.replyInput.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      sendReply();
    }
  });

  els.testButton.addEventListener('click', sendTestNotification);
  els.ringButton.addEventListener('click', ringDevice);
  els.clearFeedButton.addEventListener('click', () => {
    state.notifications = [];
    state.selectedId = null;
    renderNotifications();
    renderDetail();
  });

  els.openForwardingButton.addEventListener('click', async () => {
    openForwardingDrawer();
    await loadForwardingRules();
  });
  els.closeForwardingButton.addEventListener('click', closeForwardingDrawer);
  els.refreshForwardingButton.addEventListener('click', loadForwardingRules);
  els.closeDetailButton.addEventListener('click', closeDetailIfMobile);
  els.overlay.addEventListener('click', () => {
    closeForwardingDrawer();
    closeDetailIfMobile();
  });

  document.querySelectorAll('input[name="forwarding-mode"]').forEach(input => {
    input.addEventListener('change', event => {
      if (event.target.checked) {
        updateForwardingMode(event.target.value);
      }
    });
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      closeForwardingDrawer();
      closeDetailIfMobile();
    }
  });
}

syncTokenFromQuery();
bindEvents();
connect();
loadForwardingRules();
