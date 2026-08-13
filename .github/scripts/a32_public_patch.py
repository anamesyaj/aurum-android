from pathlib import Path

# Android version.
gradle = Path('app/build.gradle.kts')
text = gradle.read_text()
text = text.replace('versionCode = 5', 'versionCode = 6', 1)
text = text.replace('versionName = "0.3.1-a3"', 'versionName = "0.3.2-a3"', 1)
gradle.write_text(text)

# Generic authenticated speech download through Aurum Core. No provider credential
# is present in or returned to the Android client.
client = Path('app/src/main/java/ai/aurum/personal/AurumApiClient.java')
text = client.read_text()
text = text.replace('import java.io.BufferedReader;\n', 'import java.io.BufferedReader;\nimport java.io.ByteArrayOutputStream;\n', 1)
text = text.replace('    private static final int REPLY_TIMEOUT_MS = 120_000;\n', '    private static final int REPLY_TIMEOUT_MS = 120_000;\n    private static final int MAX_SPEECH_AUDIO_BYTES = 12 * 1024 * 1024;\n', 1)
callback_anchor = '''    public interface Callback {
        void onSuccess(String value);
        void onError(String message);
    }
'''
if 'public interface AudioCallback' not in text:
    text = text.replace(callback_anchor, callback_anchor + '''
    public interface AudioCallback {
        void onSuccess(byte[] audio, String contentType);
        void onError(String message);
    }
''', 1)
anchor = '    private Set<String> fetchRecentBotIds(String baseUrl, String accessToken) throws IOException, JSONException {\n'
if 'public void synthesizeSpeech(' not in text:
    text = text.replace(anchor, '''    public void synthesizeSpeech(String baseUrl, String accessToken, String text, AudioCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text == null ? "" : text.trim());
                BinaryResponse response = requestBytes(
                        "POST",
                        endpoint(baseUrl, "/api/remote/speech"),
                        body.toString(),
                        accessToken
                );
                callback.onSuccess(response.body, response.contentType);
            } catch (Exception exception) {
                callback.onError(safeError(exception));
            }
        });
    }

''' + anchor, 1)
request_anchor = '    private String request(String method, URL url, String body, String accessToken) throws IOException {\n'
if 'private static final class BinaryResponse' not in text:
    text = text.replace(request_anchor, '''    private static final class BinaryResponse {
        final byte[] body;
        final String contentType;

        BinaryResponse(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }

    private BinaryResponse requestBytes(String method, URL url, String body, String accessToken)
            throws IOException {
        if (accessToken == null || accessToken.trim().length() < 32) {
            throw new IOException("Aurum access key is not configured");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(45_000);
        connection.setRequestProperty("Accept", "audio/mpeg");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
        connection.setRequestProperty("User-Agent", "Aurum-Android-A3");
        connection.setUseCaches(false);

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String response = readAll(connection.getErrorStream());
            connection.disconnect();
            throw new IOException(
                    response == null || response.trim().isEmpty()
                            ? "HTTP " + status
                            : compactServerError(response, status)
            );
        }

        String contentType = connection.getContentType();
        byte[] audio = readBytes(connection.getInputStream(), MAX_SPEECH_AUDIO_BYTES);
        connection.disconnect();
        if (audio.length == 0) throw new IOException("Aurum Core returned empty speech audio");
        return new BinaryResponse(audio, contentType == null ? "audio/mpeg" : contentType);
    }

''' + request_anchor, 1)
read_anchor = '    private static String readAll(InputStream stream) throws IOException {\n'
if 'private static byte[] readBytes' not in text:
    text = text.replace(read_anchor, '''    private static byte[] readBytes(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Aurum speech audio is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

''' + read_anchor, 1)
client.write_text(text)

# Local voice fallback and neural MP3 playback.
voice = Path('app/src/main/java/ai/aurum/personal/VoiceController.java')
text = voice.read_text()
text = text.replace('import android.os.Looper;\n', 'import android.os.Looper;\nimport android.media.MediaPlayer;\n', 1)
text = text.replace('import android.speech.tts.UtteranceProgressListener;\n', 'import android.speech.tts.UtteranceProgressListener;\nimport android.speech.tts.Voice;\n', 1)
text = text.replace('import java.util.ArrayList;\n', 'import java.io.File;\nimport java.io.FileOutputStream;\nimport java.io.IOException;\nimport java.util.ArrayList;\n', 1)
text = text.replace('import java.util.LinkedHashSet;\n', 'import java.util.Comparator;\nimport java.util.LinkedHashSet;\n', 1)
text = text.replace('    private boolean ttsReady;\n    private String ttsLocaleTag = "unknown";\n', '    private boolean ttsReady;\n    private String ttsLocaleTag = "unknown";\n    private String ttsVoiceLabel = "default";\n    private MediaPlayer neuralPlayer;\n    private File neuralAudioFile;\n', 1)
anchor = '    public void stopAll() {\n'
if 'public void playNeuralAudio(byte[] audio)' not in text:
    text = text.replace(anchor, '''    public void playNeuralAudio(byte[] audio) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> playNeuralAudio(audio));
            return;
        }
        if (audio == null || audio.length == 0) {
            listener.onSpeechError("Neural voice returned no audio");
            return;
        }

        cancelListening();
        stopSpeakingInternal();
        try {
            neuralAudioFile = File.createTempFile("aurum-neural-", ".mp3", appContext.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(neuralAudioFile)) {
                output.write(audio);
            }
            MediaPlayer player = new MediaPlayer();
            neuralPlayer = player;
            player.setDataSource(neuralAudioFile.getAbsolutePath());
            player.setOnPreparedListener(prepared -> {
                VoiceRuntimeState.setTtsState("speaking (Core neural)");
                notifyVoiceState();
                prepared.start();
            });
            player.setOnCompletionListener(completed -> {
                cleanupNeuralPlayer();
                setLocalReadyState();
            });
            player.setOnErrorListener((failed, what, extra) -> {
                cleanupNeuralPlayer();
                VoiceRuntimeState.setTtsState("error: neural playback failed");
                notifyVoiceState();
                listener.onSpeechError("Neural voice playback failed");
                return true;
            });
            VoiceRuntimeState.setTtsState("loading Core neural voice");
            notifyVoiceState();
            player.prepareAsync();
        } catch (IOException | RuntimeException exception) {
            cleanupNeuralPlayer();
            VoiceRuntimeState.setTtsState("error: neural playback failed");
            notifyVoiceState();
            listener.onSpeechError("Neural voice playback failed");
        }
    }

''' + anchor, 1)
text = text.replace('        if (textToSpeech != null) {\n            textToSpeech.stop();', '        cleanupNeuralPlayer();\n\n        if (textToSpeech != null) {\n            textToSpeech.stop();', 1)
old = '''        ttsLocaleTag = selected.toLanguageTag();
        ttsReady = true;
        VoiceRuntimeState.setTtsState("ready (" + ttsLocaleTag + ")");
        notifyVoiceState();'''
new = '''        ttsLocaleTag = selected.toLanguageTag();
        selectBestTtsVoice(selected);
        ttsReady = true;
        setLocalReadyState();'''
if old not in text:
    raise SystemExit('TTS initialization block not found')
text = text.replace(old, new, 1)
anchor = '    private void stopSpeakingInternal() {\n'
if 'private void selectBestTtsVoice(Locale selectedLocale)' not in text:
    text = text.replace(anchor, '''    private void selectBestTtsVoice(Locale selectedLocale) {
        ttsVoiceLabel = "default";
        if (textToSpeech == null) return;
        try {
            Set<Voice> voices = textToSpeech.getVoices();
            if (voices == null || voices.isEmpty()) return;
            Voice best = voices.stream()
                    .filter(voice -> sameLocaleFamily(voice.getLocale(), selectedLocale))
                    .max(Comparator
                            .comparingInt(Voice::getQuality)
                            .thenComparingInt(voice -> voice.isNetworkConnectionRequired() ? 1 : 0)
                            .thenComparingInt(voice -> -voice.getLatency()))
                    .orElse(null);
            if (best == null) return;
            if (textToSpeech.setVoice(best) == TextToSpeech.SUCCESS) {
                ttsLocaleTag = best.getLocale().toLanguageTag();
                ttsVoiceLabel = "q" + best.getQuality()
                        + (best.isNetworkConnectionRequired() ? "-network" : "-local");
            }
        } catch (RuntimeException ignored) {
            // Locale-level fallback remains valid even when the engine hides its voice list.
        }
    }

    private static boolean sameLocaleFamily(Locale voiceLocale, Locale targetLocale) {
        if (voiceLocale == null || targetLocale == null) return false;
        if (!voiceLocale.getLanguage().equalsIgnoreCase(targetLocale.getLanguage())) return false;
        String targetCountry = targetLocale.getCountry();
        return targetCountry.isEmpty()
                || voiceLocale.getCountry().isEmpty()
                || voiceLocale.getCountry().equalsIgnoreCase(targetCountry);
    }

    private void setLocalReadyState() {
        if (!ttsReady) return;
        VoiceRuntimeState.setTtsState(
                "ready (" + ttsLocaleTag + "; " + ttsVoiceLabel + "; Core neural preferred)"
        );
        notifyVoiceState();
    }

    private void cleanupNeuralPlayer() {
        if (neuralPlayer != null) {
            try { neuralPlayer.stop(); } catch (RuntimeException ignored) { }
            try { neuralPlayer.release(); } catch (RuntimeException ignored) { }
            neuralPlayer = null;
        }
        if (neuralAudioFile != null) {
            try { neuralAudioFile.delete(); } catch (RuntimeException ignored) { }
            neuralAudioFile = null;
        }
    }

''' + anchor, 1)
old_stop = '''    private void stopSpeakingInternal() {
        if (textToSpeech == null) return;
        try {
            textToSpeech.stop();
        } catch (RuntimeException ignored) {
            // Treat stop as best-effort and preserve UI state.
        }
        if (ttsReady) {
            VoiceRuntimeState.setTtsState("ready (" + ttsLocaleTag + ")");
        }
        notifyVoiceState();
    }
'''
new_stop = '''    private void stopSpeakingInternal() {
        cleanupNeuralPlayer();
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (RuntimeException ignored) {
                // Treat stop as best-effort and preserve UI state.
            }
        }
        if (ttsReady) setLocalReadyState();
    }
'''
if old_stop not in text:
    raise SystemExit('TTS stop block not found')
text = text.replace(old_stop, new_stop, 1)
voice.write_text(text)

# Main activity prefers Core neural speech and silently falls back to local Filipino TTS.
main = Path('app/src/main/java/ai/aurum/personal/MainActivity.java')
text = main.read_text()
text = text.replace('    private String lastAurumReply = "";\n', '    private String lastAurumReply = "";\n    private int speechRequestGeneration;\n', 1)
text = text.replace('    protected void onStop() {\n        if (voiceController != null) {', '    protected void onStop() {\n        speechRequestGeneration++;\n        if (voiceController != null) {', 1)
text = text.replace('    private void stopVoice() {\n        if (voiceController != null) voiceController.stopAll();', '    private void stopVoice() {\n        speechRequestGeneration++;\n        if (voiceController != null) voiceController.stopAll();', 1)
old = '''        voiceController.speak(lastAurumReply);
    }

    private void saveBackend()'''
new = '''        speakReply(lastAurumReply);
    }

    private void speakReply(String reply) {
        if (reply == null || reply.trim().isEmpty() || voiceController == null) return;
        final String speechText = reply.trim();
        final int generation = ++speechRequestGeneration;
        String baseUrl = BackendConfig.loadBaseUrl(this);
        String accessKey = BackendCredentialStore.loadAccessToken(this);
        if (baseUrl.trim().isEmpty() || accessKey.trim().isEmpty()) {
            voiceController.speak(speechText);
            return;
        }

        VoiceRuntimeState.setTtsState("requesting Core neural voice");
        refreshVoiceUi();
        refreshDiagnostics();
        apiClient.synthesizeSpeech(baseUrl, accessKey, speechText, new AurumApiClient.AudioCallback() {
            @Override
            public void onSuccess(byte[] audio, String contentType) {
                runOnUiThread(() -> {
                    if (generation != speechRequestGeneration || voiceController == null) return;
                    voiceController.playNeuralAudio(audio);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (generation != speechRequestGeneration || voiceController == null) return;
                    voiceController.speak(speechText);
                });
            }
        });
    }

    private void saveBackend()'''
if old not in text:
    raise SystemExit('manual speech block not found')
text = text.replace(old, new, 1)
old_auto = '''                            if (speakReply
                                    && voiceController != null
                                    && voiceController.isTtsReady()
                                    && !lastAurumReply.isEmpty()) {
                                voiceController.speak(lastAurumReply);
                            }
'''
new_auto = '''                            if (speakReply && !lastAurumReply.isEmpty() && voiceController != null) {
                                speakReply(lastAurumReply);
                            }
'''
if old_auto not in text:
    raise SystemExit('automatic speech block not found')
text = text.replace(old_auto, new_auto, 1)
text = text.replace('Android A3.1 • Filipino Voice', 'Android A3.2 • Natural Filipino Voice')
text = text.replace(
    "A3.1 prefers Filipino/Philippine TextToSpeech and automatically speaks "
                        + "Aurum's reply after voice or text messages, while keeping foreground "
                        + "push-to-talk speech recognition on the authenticated Aurum Core "
                        + "conversation path.",
    "A3.2 prefers Core neural speech when configured and automatically falls back "
                        + "to the best available Filipino/Philippine Android voice. Replies stay "
                        + "automatic after voice or text messages on the authenticated Core path."
)
text = text.replace('Aurum A3.1 is ready. Replies are spoken automatically when Android TTS is ready.\\n', 'Aurum A3.2 is ready. Core neural speech is preferred; Filipino Android TTS remains the fallback.\\n')
main.write_text(text)

# Diagnostics.
diag = Path('app/src/main/java/ai/aurum/personal/DiagnosticsReport.java')
text = diag.read_text()
text = text.replace('Milestone: A3.1 Filipino Voice + Auto Reply Speech', 'Milestone: A3.2 Natural Filipino Voice')
text = text.replace('Aurum services: A3.1 authenticated text + foreground push-to-talk + auto-spoken replies', 'Aurum services: A3.2 authenticated text + push-to-talk + Core neural/local Filipino speech')
text = text.replace('Voice mode: one-shot push-to-talk; replies auto-speak; no always-on/background microphone', 'Voice mode: one-shot push-to-talk; replies auto-speak; Core neural with local fallback')
text = text.replace('TTS preference: fil-PH -> en-PH -> device default -> en-US', 'TTS preference: Core neural -> best fil-PH voice -> en-PH -> device default -> en-US')
diag.write_text(text)

print('A3.2 Android public patch applied')
