import type { CompactSeatLayoutData, SeatLayoutData } from '@/types'

/** Restore the existing UI seat model from zero-based row-major seat indexes. */
export function expandCompactSeatLayout(compact: CompactSeatLayoutData): SeatLayoutData {
  const { rows, cols, aisles, coupleRows, disabled } = compact.layout
  const disabledSet = new Set(disabled)
  const soldSet = new Set(compact.sold)
  const lockedSet = new Set(compact.locked)
  const myLockedSet = new Set(compact.myLocked)
  const coupleRowSet = new Set(coupleRows)

  const seats = Array.from({ length: rows }, (_, rowIndex) => {
    const row = rowIndex + 1
    return Array.from({ length: cols }, (_, colIndex) => {
      const col = colIndex + 1
      const index = rowIndex * cols + colIndex
      let status = 0
      if (disabledSet.has(index)) status = -1
      else if (soldSet.has(index)) status = 1
      else if (lockedSet.has(index)) status = 2
      else if (myLockedSet.has(index)) status = 3

      return {
        row,
        col,
        label: `${row}排${col}座`,
        status,
        couple: coupleRowSet.has(row),
      }
    })
  })

  return {
    hallName: compact.hallName,
    hallType: compact.hallType,
    rows,
    cols,
    aisles,
    coupleRows,
    seats,
  }
}
