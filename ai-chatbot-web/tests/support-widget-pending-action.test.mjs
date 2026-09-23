import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'

const source = await readFile(new URL('../src/components/SupportWidget.vue', import.meta.url), 'utf8')

test('support widget restores and renders server-side pending actions', () => {
  assert.match(source, /import PendingActionCard from '\.\/PendingActionCard\.vue'/)
  assert.match(source, /pendingActionAPI\.list\(targetId\)/)
  assert.match(source, /await refreshPendingActions\(requestedId\)/)
  assert.match(source, /await refreshPendingActions\(session\.id\)/)
  assert.match(source, /<PendingActionCard[\s\S]*@confirm="confirmPendingAction"[\s\S]*@cancel="cancelPendingAction"[\s\S]*@refresh="refreshPendingActionStatus"/)
})

test('support widget reconciles pending actions from the authoritative GET after a streamed answer', () => {
  assert.doesNotMatch(source, /mergePendingActions\((?:data|result\?)\.pendingActions\)/)
  assert.match(source, /finally \{[\s\S]*await refreshPendingActions\(conversationId, true\)/)
})

test('support widget decisions use the action version and recover authoritative status', () => {
  assert.match(source, /pendingActionAPI\.confirm\(actionId, action\.version\)/)
  assert.match(source, /pendingActionAPI\.cancel\(actionId, action\.version\)/)
  assert.match(source, /const recovered = await recoverPendingAction\(actionId\)/)
})
