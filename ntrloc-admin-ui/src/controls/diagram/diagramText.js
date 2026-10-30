// Text sizing for diagrams, estimated rather than measured, so layout stays a pure function: about
// 0.57em per character, plus a little slack.
export function estimateTextWidth(text, fontSize = 12) {
  return Math.max(24, text.length * fontSize * 0.567 + 9)
}

// Splits text into lines no wider than maxWidth -- on spaces, then on underscores for a long
// unspaced name like Return_to_Requestor.
export function wrapText(text, maxWidth, fontSize) {
  const charWidth = fontSize * 0.567
  function pack(tokens, glue) {
    const lines = []
    let current = ''
    for (const token of tokens) {
      const candidate = current ? `${current}${glue}${token}` : token
      if (current && candidate.length * charWidth > maxWidth) {
        lines.push(current)
        current = token
      } else {
        current = candidate
      }
    }
    if (current) lines.push(current)
    return lines
  }
  return pack(text.split(' '), ' ').flatMap((line) =>
    line.length * charWidth > maxWidth && line.includes('_') ? pack(line.split('_'), '_') : [line],
  )
}

// The size of an edge's label (DiagramEdge draws them at 12px).
export function edgeLabelSize(label) {
  return { width: estimateTextWidth(label, 12) + 8, height: 18 }
}
