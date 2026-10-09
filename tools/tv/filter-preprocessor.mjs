/* Mozilla Public License 2.0: https://mozilla.org/MPL/2.0/
 * From brave/brave-core-crx-packager, commit
 * 79a7865bf77f3c3d08c79ba407961c802b4ec582, lib/adBlockRustUtils.js.
 * Kept identical to the upstream conditional-filter preprocessing. */

const IF_CONDITIONS = new Map([
  ['ext_ublock', true],
  // [ 'ext_ubol', 'ubol' ],
  ['ext_devbuild', false],
  ['env_devbuild', false],
  ['env_chromium', true],
  ['env_edge', false],
  ['env_firefox', false],
  ['env_legacy', false],
  // [ 'env_mobile', 'mobile' ],
  // [ 'env_mv3', 'mv3' ],
  ['env_safari', false],
  ['cap_html_filtering', false],
  ['cap_user_stylesheet', true],
  ['false', false],
  ['ext_abp', false],
  ['adguard', false],
  ['adguard_app_android', false],
  ['adguard_app_ios', false],
  ['adguard_app_mac', false],
  ['adguard_app_windows', false],
  ['adguard_ext_android_cb', false],
  ['adguard_ext_chromium', true],
  ['adguard_ext_edge', false],
  ['adguard_ext_firefox', false],
  ['adguard_ext_opera', true],
  ['adguard_ext_safari', false]
])

const preprocess = (listBuffer) => {
  const [NORMAL, IF_BRAVE, IF_NOT_BRAVE, IF_WHATEVER] = [0, 1, 2, 3]
  const negateIfState = (currentState) => {
    if (currentState === IF_WHATEVER) return currentState
    return currentState === IF_BRAVE
      ? IF_NOT_BRAVE
      : IF_BRAVE
  }
  const stack = []
  const ifRegex = /^!#if (!?)(.*)$/
  const popStack = () => {
    if (stack.length === 0) {
      throw new Error(listBuffer.title + ' preprocessor error. Check for corrupted list contents.')
    }
    return stack.pop()
  }

  listBuffer.data = listBuffer.data.split('\n').filter(line => {
    line = line.trim()
    const currentIfStatePeek = stack.length === 0 ? NORMAL : stack[stack.length - 1]
    const ifMatch = line.match(ifRegex)
    if (ifMatch !== null) {
      if (currentIfStatePeek === IF_NOT_BRAVE) {
        // We are in falsey land, maintain falsey but record if-depth on stack
        stack.push(IF_NOT_BRAVE)
        return false
      }
      // eslint-disable-next-line no-unused-vars
      const [_, negate, variable] = ifMatch
      const variableValue = IF_CONDITIONS.get(variable)
      if (variableValue === undefined) {
        stack.push(IF_WHATEVER)
        return false
      }
      const conditionValue = negate === '!' ? !variableValue : variableValue
      stack.push(conditionValue ? IF_BRAVE : IF_NOT_BRAVE)
      return false
    }
    if (line === '!#else') {
      stack.push(negateIfState(popStack()))
      return false
    }
    if (line === '!#endif') {
      popStack()
      return false
    }
    return currentIfStatePeek !== IF_NOT_BRAVE
  }).join('\n')
  if (stack.length !== 0) {
    throw new Error(listBuffer.title + ' preprocessor stack not empty at end. Check for corrupted list contents.')
  }
  return listBuffer
}

export { preprocess };
