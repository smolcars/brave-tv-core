'use strict'
const $ = (id) => document.getElementById(id)
let token = '',
  epoch = '',
  state = null,
  seq = 0,
  busy = false,
  connected = false
let invite = location.hash.slice(1),
  composing = false,
  field = 0,
  lastText = '',
  dirty = false
history.replaceState(null, '', '/')
const pointers = new Map()
let gesture = null,
  motion = null,
  polling = null,
  waiting = false,
  dirtyVersion = 0
function status(text) {
  $('status').textContent = text
}
async function post(path, data) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ v: 1, ...data }),
    credentials: 'omit',
    cache: 'no-store',
    signal: AbortSignal.timeout(4000),
  })
  if (!response.ok) throw new Error('connection')
  return response.json()
}
function resetInput() {
  pointers.clear()
  gesture = motion = null
  dirty = false
  composing = false
  field = 0
  $('editor').value = ''
}
function render(result) {
  if (result.error) {
    connected = false
    state = null
    resetInput()
    status(
      result.error === 'approval'
        ? 'Waiting for approval on the TV…'
        : 'Session unavailable. Start a new invitation on the TV.',
    )
    if (result.error !== 'approval') token = ''
    disable(true)
    return
  }
  const previous = state
  state = result.state
  epoch = result.epoch
  connected = !!state
  if (!state) return
  if (previous && previous.revision !== state.revision) resetInput()
  $('pair').hidden = true
  $('remote').hidden = false
  $('disconnect').hidden = false
  status(
    state.paused
      ? 'Paused — use the TV remote for private or native screens.'
      : result.ok === false
        ? 'TV changed. Action discarded; try again.'
        : 'Connected to your TV',
  )
  disable(state.paused)
  if (state.paused) {
    $('title').textContent = 'Control paused'
    $('url').textContent = ''
    $('tabs').replaceChildren()
    resetInput()
    return
  }
  $('title').textContent = state.title || 'TV home'
  $('url').textContent = state.url || ''
  $('back').disabled = !state.back
  $('forward').disabled = !state.forward
  $('reload').textContent = state.loading ? 'Stop' : 'Reload'
  $('reload').disabled = !state.page
  $('pointer').hidden = !state.page
  const tabs = $('tabs')
  if (tabs.dataset.state !== JSON.stringify(state.tabs)) {
    tabs.dataset.state = JSON.stringify(state.tabs)
    tabs.replaceChildren()
    for (const tab of state.tabs || []) {
      const row = document.createElement('div')
      row.className = 'tab'
      row.setAttribute('aria-current', String(tab.selected))
      const select = document.createElement('button')
      select.textContent = tab.title || 'New tab'
      select.onclick = () => command('selectTab', { tab: tab.id })
      const close = document.createElement('button')
      close.textContent = '×'
      close.setAttribute('aria-label', `Close ${tab.title || 'new tab'}`)
      close.onclick = () => command('closeTab', { tab: tab.id })
      row.append(select, close)
      tabs.append(row)
    }
  }
  const editable = state.editable
  if (!editable) {
    $('editor').disabled = true
    $('fieldHint').textContent =
      'Select an eligible text field on the TV. Passwords are excluded.'
  } else {
    $('fieldHint').textContent = 'Editing the selected TV field'
    $('editor').disabled = false
    if (field !== editable.revision) {
      dirty = false
      composing = false
    }
    field = editable.revision
    if (!dirty && !composing && editable.ready) {
      $('editor').value = editable.text
      $('editor').setSelectionRange(editable.start, editable.end)
      lastText = editable.text
    }
  }
}
function disable(value) {
  document.body.classList.toggle('paused', value)
  for (const b of $('remote').querySelectorAll('button,input,textarea'))
    b.disabled = value
}
async function poll() {
  if (!token || busy || document.hidden) return
  busy = true
  try {
    polling = post('/api', { token, op: 'state' })
    render(await polling)
  } catch {
    disconnected()
  } finally {
    busy = false
    polling = null
  }
}
function disconnected() {
  connected = false
  state = null
  resetInput()
  disable(true)
  status('Disconnected. Reconnecting for fresh state…')
}
async function command(op, extra = {}) {
  const revision = state?.revision
  if (polling && !waiting) {
    waiting = true
    try {
      await polling
    } catch {
    } finally {
      waiting = false
    }
  }
  if (
    !token
    || !connected
    || !state
    || state.paused
    || revision !== state.revision
  )
    return false
  if (busy) {
    status('An action is still in progress. Try again.')
    return false
  }
  busy = true
  try {
    render(
      await post('/api', {
        token,
        op,
        epoch,
        seq: ++seq,
        revision: state.revision,
        ...extra,
      }),
    )
    return true
  } catch {
    disconnected()
    return false
  } finally {
    busy = false
  }
}
async function pair(secret) {
  if (busy) return
  busy = true
  $('pairForm').querySelector('button').disabled = true
  try {
    const result = await post('/pair', { invite: secret })
    if (!result.token) throw new Error('pairing')
    token = result.token
    status('Waiting for approval on the TV…')
  } catch {
    status('Pairing failed or expired. Start a new invitation on the TV.')
  } finally {
    busy = false
    invite = ''
    $('code').value = ''
    $('pairForm').querySelector('button').disabled = false
  }
  poll()
}
$('pairForm').onsubmit = (e) => {
  e.preventDefault()
  pair($('code').value)
}
$('addressForm').onsubmit = (e) => {
  e.preventDefault()
  command('navigate', { text: $('address').value })
}
$('reload').onclick = () => command(state?.loading ? 'stop' : 'reload')
$('showKeyboard').onclick = () => {
  $('keyboard').hidden = !$('keyboard').hidden
  if (!$('keyboard').hidden) $('editor').focus()
}
for (const b of document.querySelectorAll('[data-op]'))
  b.onclick = () => command(b.dataset.op)
for (const b of document.querySelectorAll('[data-move]'))
  b.onclick = () => {
    const [dx, dy] = b.dataset.move.split(',').map(Number)
    command('move', { dx, dy })
  }
for (const b of document.querySelectorAll('[data-scroll]'))
  b.onclick = () => command('scroll', { dx: 0, dy: Number(b.dataset.scroll) })
$('disconnect').onclick = async () => {
  const credential = token
  token = ''
  resetInput()
  disable(true)
  status('Disconnected')
  try {
    await post('/api', { token: credential, op: 'disconnect' })
  } catch {}
  $('disconnect').hidden = true
}
const pad = $('pad')
pad.onpointerdown = (e) => {
  if (!connected || !state?.page || state.paused) return
  pad.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (!gesture)
    gesture = {
      x: e.clientX,
      y: e.clientY,
      time: performance.now(),
      moved: false,
      multi: false,
      revision: state.revision,
    }
  if (pointers.size > 1) gesture.multi = true
}
pad.onpointermove = (e) => {
  const old = pointers.get(e.pointerId)
  if (!old || !gesture || gesture.revision !== state?.revision) return
  const dx = e.clientX - old.x,
    dy = e.clientY - old.y
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (Math.hypot(e.clientX - gesture.x, e.clientY - gesture.y) > 6)
    gesture.moved = true
  if (!gesture.moved) return
  const op = pointers.size > 1 ? 'scroll' : 'move'
  if (!motion || motion.op !== op) motion = { op, dx: 0, dy: 0 }
  motion.dx += dx * (op === 'move' ? 2 : 0.05)
  motion.dy += dy * (op === 'move' ? 2 : -0.05)
}
pad.onpointerup = (e) => {
  pointers.delete(e.pointerId)
  if (!pointers.size && gesture) {
    const tap =
      !gesture.moved && !gesture.multi && performance.now() - gesture.time < 450
    gesture = null
    motion = null
    if (tap) command('click')
  }
}
pad.onpointercancel = () => {
  pointers.clear()
  gesture = motion = null
}
setInterval(() => {
  if (!motion || busy) return
  const m = motion
  motion = null
  command(m.op, {
    dx: Math.max(-1000, Math.min(1000, Math.round(m.dx))),
    dy: Math.max(-1000, Math.min(1000, Math.round(m.dy))),
  })
}, 60)
// Coalesce current-field edits only. A failed request or target change discards them.
$('editor').oncompositionstart = () => {
  composing = true
}
$('editor').oncompositionend = () => {
  composing = false
  if (!dirty) dirtyVersion = state?.editable?.version
  dirty = true
}
$('editor').oninput = () => {
  if (!dirty) dirtyVersion = state?.editable?.version
  dirty = true
}
setInterval(async () => {
  if (
    !dirty
    || busy
    || !connected
    || !state?.editable?.ready
    || field !== state.editable.revision
  )
    return
  const text = $('editor').value,
    start = $('editor').selectionStart,
    end = $('editor').selectionEnd
  dirty = false
  await command('edit', {
    editable: field,
    edit: 'replace',
    version: dirtyVersion,
    text,
    start,
    end,
    composing,
  })
}, 80)
$('editor').onselect = () => {
  if (
    state?.editable
    && $('editor').selectionStart === state.editable.start
    && $('editor').selectionEnd === state.editable.end
  )
    return
  if (!dirty && !busy && !composing && state?.editable)
    command('edit', {
      editable: field,
      edit: 'select',
      version: state.editable.version,
      start: $('editor').selectionStart,
      end: $('editor').selectionEnd,
    })
}
$('delete').onclick = () =>
  command('edit', {
    editable: field,
    edit: 'delete',
    version: state?.editable?.version,
    start: 1,
    end: 0,
  })
$('finish').onclick = () =>
  command('edit', {
    editable: field,
    edit: 'finish',
    version: state?.editable?.version,
  })
document.addEventListener('visibilitychange', () => {
  resetInput()
  connected = false
  disable(true)
  if (!document.hidden) poll()
})
setInterval(poll, 500)
if (invite) pair(invite)
