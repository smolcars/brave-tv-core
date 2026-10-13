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
  dirtyVersion = 0,
  dirtyReplacementVersion = 0,
  pendingEdit = null,
  enterIntent = null,
  advancingEnter = false
const actionLabels = {
  enter: 'Enter',
  search: 'Search',
  go: 'Go',
  send: 'Send',
  done: 'Done',
}
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
  pendingEdit = null
  composing = false
  field = 0
  enterIntent = null
  $('editor').readOnly = false
  $('editor').value = ''
}
function clearPage() {
  $('title').textContent = 'Control unavailable'
  $('url').textContent = ''
  $('address').value = ''
  $('tabs').replaceChildren()
  delete $('tabs').dataset.state
  $('tabsEmpty').hidden = true
  $('fieldHint').textContent =
    'Phone typing is unavailable. Continue on your TV.'
}
function render(result) {
  if (!result.state && !result.error) result = { error: 'revoked' }
  if (result.error) {
    connected = false
    state = null
    resetInput()
    clearPage()
    $('disconnect').hidden = true
    $('pair').hidden = result.error === 'approval'
    $('approval').hidden = result.error !== 'approval'
    $('remote').hidden = true
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
  if (result.ok === false) {
    enterIntent = null
    if (pendingEdit) {
      pendingEdit = null
      dirty = composing = false
    }
  }
  if (pendingEdit && state.editable?.ready) {
    const editable = state.editable
    // Only our confirmed same-field result can advance locally coalesced typing.
    if (
      editable.revision === pendingEdit.field
      && editable.text === pendingEdit.text
      && editable.start === pendingEdit.start
      && editable.end === pendingEdit.end
    ) {
      if (enterIntent?.field === editable.revision)
        enterIntent.version = editable.version
      if (dirty) {
        dirtyVersion = editable.version
        dirtyReplacementVersion = editable.replacementVersion
      }
    } else {
      dirty = composing = false
      enterIntent = null
    }
    pendingEdit = null
  }
  $('pair').hidden = true
  $('approval').hidden = true
  $('remote').hidden = false
  $('disconnect').hidden = false
  status(
    state.paused
      ? state.pauseReason === 'controls'
        ? 'Paused — close the browser controls on the TV to continue.'
        : 'Paused — continue on the TV. Phone control is unavailable on this screen.'
      : result.ok === false
        ? 'TV changed. Action discarded; try again.'
        : state.error
          ? 'The TV page could not be loaded. Try Reload or another address.'
          : 'Connected to your TV',
  )
  disable(state.paused)
  if (state.paused) {
    clearPage()
    $('title').textContent = 'Control paused'
    resetInput()
    if (!previous?.paused) $('status').scrollIntoView({ block: 'start' })
    return
  }
  $('title').textContent = state.title || 'TV home'
  $('url').textContent = state.url || ''
  $('back').disabled = !state.back
  $('forward').disabled = !state.forward
  $('reload').textContent = state.loading ? 'Stop' : 'Reload'
  $('reload').disabled = !state.page
  $('pointer').hidden = !state.page
  $('homeState').hidden = !!state.page
  $('tabsEmpty').hidden = !!state.tabs?.length
  const tabs = $('tabs')
  if (tabs.dataset.state !== JSON.stringify(state.tabs)) {
    const focused = document.activeElement
    const focusedTab = focused?.dataset.tab
    const focusedAction = focused?.dataset.action
    tabs.dataset.state = JSON.stringify(state.tabs)
    tabs.replaceChildren()
    for (const tab of state.tabs || []) {
      const row = document.createElement('div')
      row.className = 'tab'
      row.setAttribute('aria-current', String(tab.selected))
      const select = document.createElement('button')
      select.className = 'tab-select'
      select.dataset.tab = tab.id
      select.dataset.action = 'select'
      select.textContent = tab.title || 'New tab'
      select.setAttribute('aria-current', tab.selected ? 'page' : 'false')
      select.onclick = () => command('selectTab', { tab: tab.id })
      const close = document.createElement('button')
      close.className = 'tab-close'
      close.dataset.tab = tab.id
      close.dataset.action = 'close'
      const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
      icon.setAttribute('viewBox', '0 0 24 24')
      icon.setAttribute('aria-hidden', 'true')
      const path = document.createElementNS(
        'http://www.w3.org/2000/svg',
        'path',
      )
      path.setAttribute('d', 'm6 6 12 12M18 6 6 18')
      icon.append(path)
      close.append(icon)
      close.setAttribute('aria-label', `Close ${tab.title || 'new tab'}`)
      close.onclick = () => command('closeTab', { tab: tab.id })
      row.append(select, close)
      tabs.append(row)
    }
    if (focusedTab) {
      const replacement = [...tabs.querySelectorAll('button')].find(
        (button) =>
          button.dataset.tab === focusedTab
          && button.dataset.action === focusedAction,
      )
      // Native handles can rotate when the active content owner changes.
      const selected = tabs.querySelector('.tab-select[aria-current="page"]')
      const target =
        replacement
        || (focusedAction === 'select' && selected)
        || $('tabsView').querySelector('[data-op="new"]')
      target.focus()
    }
  }
  const editable = state.editable
  if (!editable) {
    enterIntent = null
    pendingEdit = null
    dirty = composing = false
    field = 0
    $('editor').value = ''
    $('editor').disabled = true
    $('fieldHint').textContent =
      'Select an eligible text field on the TV. Passwords are excluded.'
  } else {
    // Awaiting our native edit must not blur the phone IME mid-composition.
    const awaitingEdit =
      field === editable.revision && pendingEdit?.field === editable.revision
    const awaitingEnter =
      field === editable.revision && enterIntent?.field === editable.revision
    $('editor').disabled = !editable.ready && !awaitingEdit && !awaitingEnter
    if (field !== editable.revision) {
      enterIntent = null
      $('editor').value = ''
      dirty = false
      composing = false
    }
    field = editable.revision
    const action = Object.hasOwn(actionLabels, editable.action)
      ? editable.action
      : 'enter'
    $('enter').textContent = actionLabels[action]
    $('editor').setAttribute('enterkeyhint', action)
    if (enterIntent?.sent && editable.ready) enterIntent = null
    $('fieldHint').textContent = enterIntent
      ? `Sending ${actionLabels[enterIntent.action]} to the TV…`
      : editable.ready
        ? editable.multiline
          ? 'Editing the selected TV field. Enter may add a new line.'
          : 'Editing the selected TV field'
        : 'Reading the selected TV field…'
    if (!dirty && !composing && editable.ready) {
      $('editor').value = editable.text
      $('editor').setSelectionRange(editable.start, editable.end)
      lastText = editable.text
    }
  }
  updateEnter()
}
function updateEnter() {
  const editable = state?.editable
  $('editor').readOnly = !!enterIntent
  $('enter').disabled =
    !connected
    || state?.paused
    || !editable
    || composing
    || !!enterIntent
    || !!editable.composing
    || (!editable.ready && pendingEdit?.field !== editable.revision)
  for (const id of ['delete', 'finish'])
    $(id).disabled =
      !connected || state?.paused || !editable?.ready || !!enterIntent
}
function disable(value) {
  document.body.classList.toggle('paused', value)
  for (const b of $('remote').querySelectorAll('button,input,textarea'))
    if (!b.dataset.view) b.disabled = value
  for (const id of ['delete', 'finish', 'enter'])
    $(id).disabled = value || !state?.editable?.ready
  $('pad').setAttribute('aria-disabled', String(value || !state?.page))
}
async function poll() {
  if (!token || busy || document.hidden) return
  const credential = token
  busy = true
  try {
    polling = post('/api', { token: credential, op: 'state' })
    const result = await polling
    if (token === credential) render(result)
  } catch {
    if (token === credential) disconnected()
  } finally {
    busy = false
    polling = null
  }
}
function disconnected() {
  if (!epoch) {
    render({ error: 'revoked' })
    return
  }
  connected = false
  state = null
  resetInput()
  clearPage()
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
  if (op === 'click' && motion) {
    if (!(await flushMotion())) return false
    if (
      !connected
      || !state
      || state.paused
      || revision !== state.revision
      || busy
    )
      return false
  }
  const credential = token
  busy = true
  if (op === 'edit' && (extra.edit === 'replace' || extra.edit === 'select')) {
    pendingEdit = {
      field: extra.editable,
      text: extra.edit === 'replace' ? extra.text : state.editable.text,
      start: extra.composing ? extra.text.length : extra.start,
      end: extra.composing ? extra.text.length : extra.end,
    }
  }
  try {
    const result = await post('/api', {
      token: credential,
      op,
      epoch,
      seq: ++seq,
      revision: state.revision,
      ...extra,
    })
    if (token !== credential) return false
    render(result)
    return result.ok === true
  } catch {
    if (token === credential) disconnected()
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
    epoch = ''
    $('pair').hidden = true
    $('approval').hidden = false
    status('Waiting for approval on the TV…')
  } catch {
    $('pair').hidden = false
    $('approval').hidden = true
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
const taskTabs = [...document.querySelectorAll('[role="tab"]')]
function showView(id) {
  // A task change must not carry an unfinished gesture or unsent field edit.
  resetInput()
  if (connected && state) render({ state, epoch })
  for (const view of document.querySelectorAll('.task-view'))
    view.hidden = view.id !== id
  for (const tab of taskTabs) {
    const selected = tab.dataset.view === id
    tab.setAttribute('aria-selected', String(selected))
    tab.tabIndex = selected ? 0 : -1
  }
}
for (const button of document.querySelectorAll('[data-view]'))
  button.onclick = () => {
    showView(button.dataset.view)
    if (button.getAttribute('role') !== 'tab') $('address').focus()
  }
for (const tab of taskTabs)
  tab.onkeydown = (event) => {
    const index = taskTabs.indexOf(tab)
    let next
    if (event.key === 'ArrowRight') next = (index + 1) % taskTabs.length
    else if (event.key === 'ArrowLeft')
      next = (index + taskTabs.length - 1) % taskTabs.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = taskTabs.length - 1
    else return
    event.preventDefault()
    showView(taskTabs[next].dataset.view)
    taskTabs[next].focus()
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
  render({ error: 'revoked' })
  $('disconnect').hidden = true
  try {
    await post('/api', { token: credential, op: 'disconnect' })
  } catch {}
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
    if (tap) command('click')
    else flushMotion()
  }
}
pad.onpointercancel = () => {
  pointers.clear()
  gesture = motion = null
}
function flushMotion() {
  if (!motion || busy) return
  const m = motion
  motion = null
  return command(m.op, {
    dx: Math.max(-1000, Math.min(1000, Math.round(m.dx))),
    dy: Math.max(-1000, Math.min(1000, Math.round(m.dy))),
  })
}
setInterval(flushMotion, 60)
// Coalesce current-field edits only. A failed request or target change discards them.
$('editor').addEventListener('compositionstart', () => {
  enterIntent = null
  composing = true
  updateEnter()
})
$('editor').addEventListener('compositionend', () => {
  composing = false
  if (!dirty) {
    dirtyVersion = state?.editable?.version
    dirtyReplacementVersion = state?.editable?.replacementVersion
  }
  dirty = true
  updateEnter()
})
$('editor').oninput = () => {
  if (!dirty) {
    dirtyVersion = state?.editable?.version
    dirtyReplacementVersion = state?.editable?.replacementVersion
  }
  dirty = true
}
async function flushEditor() {
  if (
    !dirty
    || busy
    || pendingEdit
    || !connected
    || !state?.editable?.ready
    || field !== state.editable.revision
  )
    return
  const text = $('editor').value,
    start = $('editor').selectionStart,
    end = $('editor').selectionEnd
  dirty = false
  return command('edit', {
    editable: field,
    edit: 'replace',
    version: dirtyVersion,
    replacementVersion: dirtyReplacementVersion,
    text,
    start,
    end,
    composing,
  })
}
function requestEnter() {
  const editable = state?.editable
  if (
    !connected
    || state.paused
    || enterIntent
    || composing
    || !editable
    || field !== editable.revision
    || (editable.composing && !dirty && !pendingEdit)
    || (!editable.ready && pendingEdit?.field !== field)
  )
    return false
  enterIntent = {
    epoch,
    revision: state.revision,
    field,
    version: editable.version,
    action: Object.hasOwn(actionLabels, editable.action)
      ? editable.action
      : 'enter',
    text: $('editor').value,
    start: $('editor').selectionStart,
    end: $('editor').selectionEnd,
    deadline: performance.now() + 2000,
    sent: false,
  }
  // A pending selection acknowledgement can race the phone's latest caret.
  if (
    !dirty
    && (enterIntent.text !== editable.text
      || enterIntent.start !== editable.start
      || enterIntent.end !== editable.end)
  ) {
    dirtyVersion = editable.version
    dirtyReplacementVersion = editable.replacementVersion
    dirty = true
  }
  updateEnter()
  $('editor').focus({ preventScroll: true })
  return true
}
async function advanceEnter() {
  const intent = enterIntent
  if (!intent || intent.sent || advancingEnter) return
  const editable = state?.editable
  if (
    !connected
    || state.paused
    || epoch !== intent.epoch
    || state.revision !== intent.revision
    || editable?.revision !== intent.field
    || composing
    || performance.now() > intent.deadline
  ) {
    enterIntent = null
    updateEnter()
    status('TV changed or typing took too long. Enter discarded; try again.')
    return
  }
  if (busy || pendingEdit || !editable.ready) return
  advancingEnter = true
  try {
    if (dirty) {
      await flushEditor()
      return
    }
    if (
      editable.version !== intent.version
      || editable.composing
      || editable.text !== intent.text
      || editable.start !== intent.start
      || editable.end !== intent.end
    ) {
      enterIntent = null
      status('TV field changed. Enter discarded; try again.')
      return
    }
    intent.sent = true
    await command('edit', {
      editable: intent.field,
      version: intent.version,
      edit: 'enter',
      action: intent.action,
    })
  } finally {
    advancingEnter = false
    updateEnter()
  }
}
setInterval(() => (enterIntent ? advanceEnter() : flushEditor()), 80)
$('enter').onclick = requestEnter
$('editor').addEventListener('keydown', (event) => {
  if (
    event.key !== 'Enter'
    || composing
    || event.isComposing
    || event.keyCode === 229
  )
    return
  if (event.repeat || enterIntent || requestEnter()) event.preventDefault()
})
$('editor').addEventListener('beforeinput', (event) => {
  if (
    !event.cancelable
    || composing
    || event.isComposing
    || !['insertLineBreak', 'insertParagraph'].includes(event.inputType)
  )
    return
  if (enterIntent || requestEnter()) event.preventDefault()
})
$('editor').onselect = () => {
  if (
    state?.editable
    && $('editor').selectionStart === state.editable.start
    && $('editor').selectionEnd === state.editable.end
  )
    return
  if (
    !dirty
    && !busy
    && !pendingEdit
    && !composing
    && !enterIntent
    && state?.editable?.ready
  )
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
