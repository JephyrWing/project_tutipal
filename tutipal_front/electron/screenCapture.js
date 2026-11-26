// This isolated renderer receives no user text, URLs, cookies, IPC or preload.
async function captureOneFrame() {
  let stream
  const video = document.createElement('video')
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 1 }, audio: false })
    video.muted = true
    const loaded = new Promise(resolve => { video.onloadeddata = resolve })
    video.srcObject = stream
    await video.play()
    if (video.readyState < 2) await loaded
    const canvas = document.createElement('canvas')
    const scale = Math.min(1, 1280 / video.videoWidth, 800 / video.videoHeight)
    canvas.width = Math.max(1, Math.round(video.videoWidth * scale))
    canvas.height = Math.max(1, Math.round(video.videoHeight * scale))
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
    return canvas.toDataURL('image/jpeg', 0.7)
  } finally {
    stream?.getTracks().forEach(track => track.stop())
    video.srcObject = null
  }
}
