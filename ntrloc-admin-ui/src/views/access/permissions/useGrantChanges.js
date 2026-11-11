import { useMemo } from 'react'
import { useDraftMode } from '../../../global/viewmodel/useDraftMode'
import { indexGrantChanges } from './grantModel'

// The current draft's grant changes, indexed (see indexGrantChanges); none while live.
export function useGrantChanges() {
  const { grantChanges } = useDraftMode()
  return useMemo(() => indexGrantChanges(grantChanges), [grantChanges])
}
