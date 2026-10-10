import { concatFloat32, resample, rms, usableTranscript } from './audio.js';

const IDLE = '';
const VOICE = 0.015;

function cloudSpeechIsUnreliable() {
  return /Electron|Cursor\//.test(navigator.userAgent);
}

function rememberLocal() {
  try {
    sessionStorage.setItem('vela-speech-mode', 'local');
  } catch {
    /* private mode */
  }
}

function savedMode() {
  try {
    return sessionStorage.getItem('vela-speech-mode');
  } catch {
    return null;
  }
}

export function canSpeak() {
  return typeof window.VellaNative?.speak === 'function' || 'speechSynthesis' in window;
}

export function unlockSpeech() {
  if ('speechSynthesis' in window) window.speechSynthesis.resume();
}

function englishVoice() {
  const voices = window.speechSynthesis.getVoices()
    .filter((voice) => voice.lang.toLowerCase().replace('_', '-').startsWith('en-us'))
    .sort((a, b) => a.name.localeCompare(b.name));
  return voices.find((voice) => /x-iog/i.test(voice.name))
    || voices.find((voice) => /female_3|male_3/i.test(voice.name))
    || null;
}

export function speak(text) {
  if (!text) return;
  if (window.VellaNative?.speak) {
    window.VellaNative.speak(text);
    return;
  }
  if (!canSpeak()) return;
  const say = () => {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    const voice = englishVoice();
    utterance.lang = 'en-US';
    utterance.rate = 1;
    utterance.pitch = 1;
    if (voice) utterance.voice = voice;
    window.speechSynthesis.speak(utterance);
  };
  if (window.speechSynthesis.getVoices().length) {
    say();
    return;
  }
  let said = false;
  const once = () => {
    if (said) return;
    said = true;
    window.speechSynthesis.removeEventListener('voiceschanged', once);
    say();
  };
  window.speechSynthesis.addEventListener('voiceschanged', once);
  window.speechSynthesis.getVoices();
  window.setTimeout(once, 400);
}

export function silence() {
  if (window.VellaNative?.silence) window.VellaNative.silence();
  if ('speechSynthesis' in window) window.speechSynthesis.cancel();
}

function messageFor(code) {
  if (code === 'not-allowed') return 'Allow the microphone for this site, then tap the mic again.';
  if (code === 'audio-capture') return 'No microphone was found. Plug one in, or type the command.';
  if (code === 'no-speech') return "I didn't hear anything. Tap the mic and try again.";
  return 'Speech recognition stopped. Tap the mic and try again, or type the command.';
}

export function createSpeechController({ onListen, onTranscript, onFinal, onStatus, onError }) {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let mode = SpeechRecognition && savedMode() !== 'local' && !cloudSpeechIsUnreliable() ? 'web' : 'local';
  let session = null;
  let modelPromise = null;
  let webListening = false;
  let delivered = false;

  const web = SpeechRecognition ? createWebRecognizer() : null;

  return {
    toggle() {
      silence();
      if (session) {
        session.stop();
        return;
      }
      if (webListening) {
        web.stop();
        return;
      }
      delivered = false;
      openMic();
    },
  };

  async function openMic() {
    onStatus('Opening the mic…');
    const context = mode === 'local' ? new AudioContext() : null;
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
      });
    } catch {
      context?.close();
      onError('Allow the microphone for this site, then tap the mic again.');
      return;
    }
    if (mode === 'web' && web) {
      stream.getTracks().forEach((track) => track.stop());
      try {
        web.start();
      } catch {
        onError('The microphone is already starting. Tap it again.');
      }
      return;
    }
    startLocal(context, stream);
  }

  function createWebRecognizer() {
    const recognition = new SpeechRecognition();
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.lang = 'en-US';
    recognition.onstart = () => {
      webListening = true;
      onListen(true);
      onStatus('Listening…');
    };
    recognition.onend = () => {
      webListening = false;
      if (mode !== 'web') return;
      onListen(false);
      onStatus(IDLE);
    };
    recognition.onerror = (event) => {
      const code = event.error;
      if (code === 'aborted') return;
      if (code === 'network' || code === 'service-not-allowed') {
        mode = 'local';
        rememberLocal();
        webListening = false;
        onListen(false);
        const context = new AudioContext();
        context.resume().then(() => {
          navigator.mediaDevices.getUserMedia({
            audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
          }).then((stream) => {
            if (session) {
              stream.getTracks().forEach((track) => track.stop());
              return;
            }
            startLocal(context, stream);
          }).catch(() => {
            context.close();
            onStatus(IDLE);
            onError('Allow the microphone for this site, then tap the mic again.');
          });
        });
        return;
      }
      if (code === 'no-speech') return;
      onError(messageFor(code));
    };
    recognition.onresult = (event) => {
      let interim = '';
      let finalText = '';
      for (let i = event.resultIndex; i < event.results.length; i += 1) {
        const piece = event.results[i][0].transcript;
        if (event.results[i].isFinal) finalText += piece;
        else interim += piece;
      }
      if (interim) onTranscript(interim);
      if (finalText.trim()) {
        delivered = true;
        onFinal(finalText.trim());
      }
    };
    return recognition;
  }

  function loadModel() {
    if (!modelPromise) {
      modelPromise = import('@huggingface/transformers').then(({ pipeline }) => pipeline(
        'automatic-speech-recognition',
        'Xenova/whisper-tiny.en',
        {
          dtype: 'q8',
          device: 'wasm',
          progress_callback(info) {
          if (info.status === 'progress_total' && session) {
            onStatus(info.progress < 100
              ? `Listening… downloading speech model ${Math.round(info.progress)}%`
              : 'Listening…');
          }
          },
        },
      )).catch((error) => {
        modelPromise = null;
        throw error;
      });
    }
    return modelPromise;
  }

  function startLocal(context, stream) {
    const sampleRate = context.sampleRate;
    const chunks = [];
    const startedAt = performance.now();
    let lastVoiceAt = startedAt;
    let heardVoice = false;
    let stopped = false;
    let modelReady = false;
    modelTask.then(() => {
      modelReady = true;
      if (!stopped) onStatus('Listening…');
    }).catch(() => {});
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(4096, 1, 1);
    const mute = context.createGain();
    mute.gain.value = 0;
    const modelTask = loadModel();
    processor.onaudioprocess = (event) => {
      if (stopped) return;
      const copy = new Float32Array(event.inputBuffer.getChannelData(0));
      chunks.push(copy);
      const now = performance.now();
      if (rms(copy) >= VOICE) {
        heardVoice = true;
        lastVoiceAt = now;
      }
      const cap = modelReady || heardVoice ? 15000 : 60000;
      const paused = heardVoice && now - startedAt >= 700 && now - lastVoiceAt >= 1000;
      if (now - startedAt >= cap || paused) session.stop();
    };
    source.connect(processor);
    processor.connect(mute);
    mute.connect(context.destination);
    onListen(true);
    onStatus('Listening…');
    session = {
      stop() {
        if (stopped) return;
        stopped = true;
        session = null;
        processor.onaudioprocess = null;
        processor.disconnect();
        source.disconnect();
        mute.disconnect();
        stream.getTracks().forEach((track) => track.stop());
        context.close();
        onListen(false);
        finish(chunks, sampleRate, heardVoice, modelTask);
      },
    };
  }

  async function finish(chunks, sampleRate, heardVoice, modelTask) {
    if (!heardVoice) {
      onStatus(IDLE);
      onError("I didn't hear anything. Tap the mic and try again.");
      return;
    }
    onStatus('Working out what you said…');
    try {
      const audio = resample(concatFloat32(chunks), sampleRate, 16000);
      const transcriber = await modelTask;
      const result = await transcriber(audio);
      const text = usableTranscript(result?.text);
      onStatus(IDLE);
      if (!text) onError("I didn't catch that. Tap the mic and try again.");
      else onFinal(text);
    } catch {
      onStatus(IDLE);
      onError("I couldn't transcribe that. Check your connection, or type the command.");
    }
  }
}
