package ai.aurum.personal;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaPlayer;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class VoiceController {
    public interface Listener {
        void onVoiceStateChanged();
        void onPartialTranscript(String text);
        void onFinalTranscript(String text);
        void onSpeechError(String message);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer speechRecognizer;
    private boolean listening;

    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private String ttsLocaleTag = "unknown";
    private String ttsVoiceLabel = "default";
    private MediaPlayer neuralPlayer;
    private File neuralAudioFile;

    public VoiceController(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        VoiceRuntimeState.setSpeechState("idle");
        VoiceRuntimeState.setTtsState("initializing");
        VoiceRuntimeState.setDetectedLanguage(null);
        notifyVoiceState();

        textToSpeech = new TextToSpeech(
                appContext,
                status -> mainHandler.post(() -> handleTtsInitialization(status))
        );
    }

    public static boolean isRecognitionAvailable(Context context) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(context);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean isTtsReady() {
        return ttsReady;
    }

    public void startListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::startListening);
            return;
        }

        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            VoiceRuntimeState.setSpeechState("microphone permission required");
            notifyVoiceState();
            listener.onSpeechError("Microphone permission is required for voice input");
            return;
        }

        if (!isRecognitionAvailable(appContext)) {
            VoiceRuntimeState.setSpeechState("speech recognizer unavailable");
            notifyVoiceState();
            listener.onSpeechError("No Android speech recognition service is available");
            return;
        }

        stopSpeakingInternal();
        ensureSpeechRecognizer();

        if (listening) {
            try {
                speechRecognizer.cancel();
            } catch (RuntimeException ignored) {
                // A fresh one-shot session is started below.
            }
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Aurum");

        // Keep recognition on the user's configured language rather than forcing English.
        // On newer Android releases, ask the recognizer to report detected language for
        // Filipino/English/Taglish diagnostics without requiring automatic language switching.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        }

        VoiceRuntimeState.setDetectedLanguage(null);
        VoiceRuntimeState.setSpeechState("starting");
        listening = true;
        notifyVoiceState();

        try {
            speechRecognizer.startListening(intent);
        } catch (RuntimeException exception) {
            listening = false;
            VoiceRuntimeState.setSpeechState("error: recognizer could not start");
            notifyVoiceState();
            listener.onSpeechError("Android speech recognition could not start");
        }
    }

    public void cancelListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::cancelListening);
            return;
        }
        if (speechRecognizer != null && listening) {
            try {
                speechRecognizer.cancel();
            } catch (RuntimeException ignored) {
                // State is still reset locally.
            }
        }
        listening = false;
        if (!VoiceRuntimeState.speechState().startsWith("error:")) {
            VoiceRuntimeState.setSpeechState("idle");
        }
        notifyVoiceState();
    }

    public void speak(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> speak(text));
            return;
        }

        if (!ttsReady || textToSpeech == null) {
            VoiceRuntimeState.setTtsState("not ready");
            notifyVoiceState();
            return;
        }

        String bounded = VoiceTextPolicy.boundedSpeechText(
                text,
                TextToSpeech.getMaxSpeechInputLength()
        );
        if (bounded.isEmpty()) return;

        cancelListening();
        String utteranceId = "aurum-" + UUID.randomUUID();
        int result = textToSpeech.speak(
                bounded,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
        );
        if (result == TextToSpeech.ERROR) {
            VoiceRuntimeState.setTtsState("error: synthesis request rejected");
            notifyVoiceState();
        }
    }

    public void playNeuralAudio(byte[] audio) {
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

    public void stopAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stopAll);
            return;
        }
        cancelListening();
        stopSpeakingInternal();
    }

    public void stopTransientActivity() {
        stopAll();
    }

    public void shutdown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::shutdown);
            return;
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (RuntimeException ignored) {
                // Continue releasing resources.
            }
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        listening = false;

        cleanupNeuralPlayer();

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        ttsReady = false;
        VoiceRuntimeState.setSpeechState("released");
        VoiceRuntimeState.setTtsState("released");
    }

    static String speechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "speech recognizer client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "microphone permission denied";
            case SpeechRecognizer.ERROR_NETWORK:
                return "speech recognition network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "speech recognition network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no speech match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "speech recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "speech recognition service error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "no speech detected";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "speech recognition service disconnected";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "too many speech recognition requests";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "speech language not supported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "speech language unavailable";
            default:
                return "speech recognizer error " + error;
        }
    }

    private void ensureSpeechRecognizer() {
        if (speechRecognizer != null) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                VoiceRuntimeState.setSpeechState("listening");
                notifyVoiceState();
            }

            @Override
            public void onBeginningOfSpeech() {
                VoiceRuntimeState.setSpeechState("listening: speech detected");
                notifyVoiceState();
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                VoiceRuntimeState.setSpeechState("processing");
                notifyVoiceState();
            }

            @Override
            public void onError(int error) {
                listening = false;
                String message = speechErrorMessage(error);
                VoiceRuntimeState.setSpeechState("error: " + message);
                notifyVoiceState();
                listener.onSpeechError(message);
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> candidates = results == null
                        ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String finalText = VoiceTextPolicy.firstNonBlank(candidates);
                if (finalText.isEmpty()) {
                    VoiceRuntimeState.setSpeechState("error: no speech match");
                    notifyVoiceState();
                    listener.onSpeechError("No speech match");
                    return;
                }
                VoiceRuntimeState.setSpeechState("idle");
                notifyVoiceState();
                listener.onFinalTranscript(finalText);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> candidates = partialResults == null
                        ? null
                        : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String partial = VoiceTextPolicy.firstNonBlank(candidates);
                if (!partial.isEmpty()) listener.onPartialTranscript(partial);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}

            @Override
            public void onLanguageDetection(Bundle results) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || results == null) {
                    return;
                }
                VoiceRuntimeState.setDetectedLanguage(
                        results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                );
                notifyVoiceState();
            }
        });
    }

    private void handleTtsInitialization(int status) {
        if (textToSpeech == null) return;

        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            VoiceRuntimeState.setTtsState("unavailable");
            notifyVoiceState();
            return;
        }

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    VoiceRuntimeState.setTtsState("speaking (" + ttsLocaleTag + ")");
                    notifyVoiceState();
                });
            }

            @Override
            public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    VoiceRuntimeState.setTtsState("ready (" + ttsLocaleTag + ")");
                    notifyVoiceState();
                });
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    VoiceRuntimeState.setTtsState("error: synthesis failed");
                    notifyVoiceState();
                });
            }
        });

        Locale selected = selectTtsLocale();
        if (selected == null) {
            ttsReady = false;
            VoiceRuntimeState.setTtsState("unavailable: no supported language");
            notifyVoiceState();
            return;
        }

        ttsLocaleTag = selected.toLanguageTag();
        selectBestTtsVoice(selected);
        ttsReady = true;
        setLocalReadyState();
    }

    private Locale selectTtsLocale() {
        Set<Locale> candidates = new LinkedHashSet<>();

        // Aurum's primary conversational voice is Filipino/Taglish. Prefer the explicit
        // Philippine voices before Locale.getDefault(); the Android UI language can be en-US
        // even when the user's Text-to-speech setting is Filipino (Philippines).
        candidates.add(Locale.forLanguageTag("fil-PH"));
        candidates.add(Locale.forLanguageTag("en-PH"));
        candidates.add(Locale.getDefault());
        candidates.add(Locale.US);

        for (Locale locale : candidates) {
            try {
                int availability = textToSpeech.isLanguageAvailable(locale);
                if (availability < TextToSpeech.LANG_AVAILABLE) continue;
                int result = textToSpeech.setLanguage(locale);
                if (result >= TextToSpeech.LANG_AVAILABLE) return locale;
            } catch (RuntimeException ignored) {
                // Try the next Filipino/Philippine/device/English fallback.
            }
        }
        return null;
    }

    private void selectBestTtsVoice(Locale selectedLocale) {
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

    private void stopSpeakingInternal() {
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

    private void notifyVoiceState() {
        if (listener != null) listener.onVoiceStateChanged();
    }
}
