package ai.aurum.personal;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 4103;

    private final AurumApiClient apiClient = new AurumApiClient();

    private VoiceController voiceController;
    private TextView diagnosticsView;
    private TextView connectionView;
    private TextView transcriptView;
    private TextView voiceStatusView;
    private TextView voiceTranscriptView;
    private EditText backendInput;
    private EditText accessKeyInput;
    private EditText messageInput;
    private Button sendButton;
    private Button micButton;
    private Button speakButton;

    private boolean conversationBusy;
    private String lastAurumReply = "";
    private int speechRequestGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Aurum");
        setContentView(buildContent());

        voiceController = new VoiceController(this, new VoiceController.Listener() {
            @Override
            public void onVoiceStateChanged() {
                refreshVoiceUi();
                refreshDiagnostics();
            }

            @Override
            public void onPartialTranscript(String text) {
                voiceTranscriptView.setText("Hearing: " + text);
            }

            @Override
            public void onFinalTranscript(String text) {
                voiceTranscriptView.setText("Heard: " + text);
                messageInput.setText(text);
                sendMessageText(text, true);
            }

            @Override
            public void onSpeechError(String message) {
                voiceTranscriptView.setText("Voice input: " + message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        backendInput.setText(BackendConfig.loadBaseUrl(this));
        refreshConnectionLabel();
        refreshVoiceUi();
        refreshDiagnostics();
    }

    @Override
    protected void onStop() {
        speechRequestGeneration++;
        if (voiceController != null) {
            // A3 is deliberately foreground push-to-talk only. Do not retain microphone
            // ownership or TTS playback after the activity leaves the foreground.
            voiceController.stopTransientActivity();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (voiceController != null) voiceController.shutdown();
        apiClient.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            VoiceRuntimeState.setSpeechState("permission granted");
            refreshVoiceUi();
            refreshDiagnostics();
            startVoiceRecognition();
        } else {
            VoiceRuntimeState.setSpeechState("microphone permission denied");
            refreshVoiceUi();
            refreshDiagnostics();
            Toast.makeText(
                    this,
                    "Microphone permission is required only for voice input. Text chat still works.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private View buildContent() {
        int pad = dp(20);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Aurum");
        title.setTextSize(30f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView milestone = new TextView(this);
        milestone.setText("Android A3.2 • Natural Filipino Voice");
        milestone.setTextSize(17f);
        milestone.setPadding(0, dp(6), 0, dp(10));
        content.addView(milestone);

        TextView description = new TextView(this);
        description.setText(
                "A3.1 prefers Filipino/Philippine TextToSpeech and automatically speaks Aurum's "
                        + "reply after voice or text messages, while keeping foreground push-to-talk "
                        + "speech recognition on the authenticated Aurum Core conversation path."
        );
        description.setTextSize(15f);
        description.setPadding(0, 0, 0, dp(12));
        content.addView(description);

        TextView securityNote = new TextView(this);
        securityNote.setText(
                "Security: Android still uses only the authenticated /api/remote/* Core surface. "
                        + "The access key remains protected with Android Keystore and never appears "
                        + "in diagnostics. Microphone permission is requested only when Speak to Aurum "
                        + "is invoked; A3 has no always-on or background microphone."
        );
        securityNote.setTextSize(13f);
        securityNote.setPadding(0, 0, 0, dp(14));
        content.addView(securityNote);

        TextView backendLabel = new TextView(this);
        backendLabel.setText("Aurum Core URL");
        backendLabel.setTypeface(Typeface.DEFAULT_BOLD);
        backendLabel.setTextSize(15f);
        content.addView(backendLabel);

        backendInput = new EditText(this);
        backendInput.setHint("https://your-private-aurum-core.example");
        backendInput.setSingleLine(true);
        backendInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        content.addView(backendInput, matchWidth());

        LinearLayout backendButtons = new LinearLayout(this);
        backendButtons.setOrientation(LinearLayout.HORIZONTAL);
        backendButtons.setGravity(Gravity.CENTER_HORIZONTAL);

        Button saveBackend = new Button(this);
        saveBackend.setText("Save Core URL");
        saveBackend.setOnClickListener(view -> saveBackend());
        backendButtons.addView(saveBackend, weightedHalf());

        Button testBackend = new Button(this);
        testBackend.setText("Test connection");
        testBackend.setOnClickListener(view -> testBackend());
        backendButtons.addView(testBackend, weightedHalf());
        content.addView(backendButtons, matchWidth());

        TextView accessKeyLabel = new TextView(this);
        accessKeyLabel.setText("Aurum remote access key");
        accessKeyLabel.setTypeface(Typeface.DEFAULT_BOLD);
        accessKeyLabel.setTextSize(15f);
        accessKeyLabel.setPadding(0, dp(8), 0, 0);
        content.addView(accessKeyLabel);

        accessKeyInput = new EditText(this);
        accessKeyInput.setHint(BackendCredentialStore.hasAccessToken(this)
                ? "Access key saved securely"
                : "Paste the 32+ character access key");
        accessKeyInput.setSingleLine(true);
        accessKeyInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        content.addView(accessKeyInput, matchWidth());

        LinearLayout authButtons = new LinearLayout(this);
        authButtons.setOrientation(LinearLayout.HORIZONTAL);
        authButtons.setGravity(Gravity.CENTER_HORIZONTAL);

        Button saveAccessKey = new Button(this);
        saveAccessKey.setText("Save access key");
        saveAccessKey.setOnClickListener(view -> saveAccessKey());
        authButtons.addView(saveAccessKey, weightedHalf());

        Button clearAccessKey = new Button(this);
        clearAccessKey.setText("Clear access key");
        clearAccessKey.setOnClickListener(view -> clearAccessKey());
        authButtons.addView(clearAccessKey, weightedHalf());
        content.addView(authButtons, matchWidth());

        connectionView = new TextView(this);
        connectionView.setTypeface(Typeface.DEFAULT_BOLD);
        connectionView.setTextSize(14f);
        connectionView.setPadding(0, dp(8), 0, dp(16));
        content.addView(connectionView);

        TextView conversationLabel = new TextView(this);
        conversationLabel.setText("Conversation");
        conversationLabel.setTypeface(Typeface.DEFAULT_BOLD);
        conversationLabel.setTextSize(18f);
        content.addView(conversationLabel);

        transcriptView = new TextView(this);
        transcriptView.setText(
                "Aurum A3.2 is ready. Core neural speech is preferred; Filipino Android TTS remains the fallback.\n"
        );
        transcriptView.setTextSize(15f);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setPadding(dp(8), dp(10), dp(8), dp(10));
        content.addView(transcriptView, matchWidth());

        TextView voiceLabel = new TextView(this);
        voiceLabel.setText("Voice");
        voiceLabel.setTypeface(Typeface.DEFAULT_BOLD);
        voiceLabel.setTextSize(16f);
        voiceLabel.setPadding(0, dp(8), 0, dp(2));
        content.addView(voiceLabel);

        voiceStatusView = new TextView(this);
        voiceStatusView.setTextSize(13f);
        voiceStatusView.setPadding(0, 0, 0, dp(4));
        content.addView(voiceStatusView, matchWidth());

        voiceTranscriptView = new TextView(this);
        voiceTranscriptView.setText("Voice transcript: waiting for push-to-talk.");
        voiceTranscriptView.setTextSize(14f);
        voiceTranscriptView.setTextIsSelectable(true);
        voiceTranscriptView.setPadding(dp(8), dp(6), dp(8), dp(6));
        content.addView(voiceTranscriptView, matchWidth());

        LinearLayout voiceButtons = new LinearLayout(this);
        voiceButtons.setOrientation(LinearLayout.HORIZONTAL);
        voiceButtons.setGravity(Gravity.CENTER_HORIZONTAL);

        micButton = new Button(this);
        micButton.setText("Speak to Aurum");
        micButton.setOnClickListener(view -> beginVoiceInput());
        voiceButtons.addView(micButton, weightedHalf());

        Button stopVoiceButton = new Button(this);
        stopVoiceButton.setText("Stop voice");
        stopVoiceButton.setOnClickListener(view -> stopVoice());
        voiceButtons.addView(stopVoiceButton, weightedHalf());
        content.addView(voiceButtons, matchWidth());

        speakButton = new Button(this);
        speakButton.setText("Speak last Aurum reply");
        speakButton.setEnabled(false);
        speakButton.setOnClickListener(view -> speakLastReply());
        content.addView(speakButton, matchWidth());

        messageInput = new EditText(this);
        messageInput.setHint("Message Aurum…");
        messageInput.setMinLines(2);
        messageInput.setMaxLines(6);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        content.addView(messageInput, matchWidth());

        sendButton = new Button(this);
        sendButton.setText("Send to Aurum");
        sendButton.setOnClickListener(view -> sendMessage());
        content.addView(sendButton, matchWidth());

        TextView diagnosticsLabel = new TextView(this);
        diagnosticsLabel.setText("Phone diagnostics");
        diagnosticsLabel.setTypeface(Typeface.DEFAULT_BOLD);
        diagnosticsLabel.setTextSize(18f);
        diagnosticsLabel.setPadding(0, dp(20), 0, dp(8));
        content.addView(diagnosticsLabel);

        diagnosticsView = new TextView(this);
        diagnosticsView.setTextSize(13f);
        diagnosticsView.setTypeface(Typeface.MONOSPACE);
        diagnosticsView.setTextIsSelectable(true);
        diagnosticsView.setPadding(0, 0, 0, dp(12));
        content.addView(diagnosticsView);

        Button refresh = new Button(this);
        refresh.setText("Refresh diagnostics");
        refresh.setOnClickListener(view -> refreshDiagnostics());
        content.addView(refresh, matchWidth());

        Button copy = new Button(this);
        copy.setText("Copy diagnostic report");
        copy.setOnClickListener(view -> copyDiagnostics());
        content.addView(copy, matchWidth());

        Button share = new Button(this);
        share.setText("Share diagnostic report");
        share.setOnClickListener(view -> shareDiagnostics());
        content.addView(share, matchWidth());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        return scrollView;
    }

    private void beginVoiceInput() {
        if (conversationBusy) {
            Toast.makeText(this, "Wait for the current Aurum reply first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!VoiceController.isRecognitionAvailable(this)) {
            VoiceRuntimeState.setSpeechState("speech recognizer unavailable");
            refreshVoiceUi();
            refreshDiagnostics();
            Toast.makeText(
                    this,
                    "No Android speech recognition service is available on this phone",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            VoiceRuntimeState.setSpeechState("microphone permission requested");
            refreshVoiceUi();
            refreshDiagnostics();
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
            return;
        }

        startVoiceRecognition();
    }

    private void startVoiceRecognition() {
        if (voiceController == null) return;
        voiceTranscriptView.setText("Listening… speak naturally in Filipino, English, or Taglish.");
        voiceController.startListening();
    }

    private void stopVoice() {
        speechRequestGeneration++;
        if (voiceController != null) voiceController.stopAll();
        voiceTranscriptView.setText("Voice stopped.");
        refreshVoiceUi();
        refreshDiagnostics();
    }

    private void speakLastReply() {
        if (lastAurumReply.trim().isEmpty()) {
            Toast.makeText(this, "No Aurum reply is available to speak yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (voiceController == null || !voiceController.isTtsReady()) {
            Toast.makeText(this, "Android TextToSpeech is not ready", Toast.LENGTH_LONG).show();
            return;
        }
        speakReply(lastAurumReply);
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

    private void saveBackend() {
        BackendConfig.Validation validation = BackendConfig.validate(backendInput.getText().toString());
        if (!validation.valid) {
            Toast.makeText(this, validation.error, Toast.LENGTH_LONG).show();
            return;
        }
        BackendConfig.saveBaseUrl(this, validation.normalized);
        backendInput.setText(validation.normalized);
        BackendConfig.recordConnectionState(this, "saved; not tested");
        refreshConnectionLabel();
        refreshDiagnostics();
        Toast.makeText(this, "Aurum Core URL saved", Toast.LENGTH_SHORT).show();
    }

    private void testBackend() {
        BackendConfig.Validation validation = validateAndSaveCurrentBackend();
        if (validation == null) return;

        String accessKey = resolveAccessKey();
        if (accessKey == null) return;

        setConnectionState("testing…");
        apiClient.testConnection(validation.normalized, accessKey, new AurumApiClient.Callback() {
            @Override
            public void onSuccess(String value) {
                runOnUiThread(() -> {
                    setConnectionState("connected");
                    appendTranscript("System", "Aurum Core connection test passed.");
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setConnectionState("error: " + message);
                    appendTranscript("System", "Core connection failed: " + message);
                });
            }
        });
    }

    private void sendMessage() {
        sendMessageText(messageInput.getText().toString().trim(), true);
    }

    private void sendMessageText(String text, boolean speakReply) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "Enter a message for Aurum", Toast.LENGTH_SHORT).show();
            return;
        }
        text = text.trim();

        if (conversationBusy) {
            Toast.makeText(this, "Wait for the current Aurum reply first", Toast.LENGTH_SHORT).show();
            return;
        }

        BackendConfig.Validation validation = validateAndSaveCurrentBackend();
        if (validation == null) {
            appendTranscript("System", "Aurum Core is not configured. Your message was not sent.");
            return;
        }

        String accessKey = resolveAccessKey();
        if (accessKey == null) {
            appendTranscript(
                    "System",
                    "Aurum remote access key is not configured. Your message was not sent."
            );
            return;
        }

        final String messageText = text;
        appendTranscript("You", messageText);
        messageInput.setText("");
        setConversationBusy(true);
        setConnectionState("sending / waiting for Aurum…");

        apiClient.sendMessage(
                validation.normalized,
                accessKey,
                messageText,
                new AurumApiClient.Callback() {
                    @Override
                    public void onSuccess(String reply) {
                        runOnUiThread(() -> {
                            setConversationBusy(false);
                            setConnectionState("connected");
                            lastAurumReply = reply == null ? "" : reply.trim();
                            appendTranscript("Aurum", lastAurumReply);
                            refreshVoiceUi();
                            if (speakReply && !lastAurumReply.isEmpty() && voiceController != null) {
                                speakReply(lastAurumReply);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            setConversationBusy(false);
                            setConnectionState("error: " + message);
                            appendTranscript(
                                    "System",
                                    "Aurum could not complete this message: " + message
                            );
                        });
                    }
                }
        );
    }

    private void setConversationBusy(boolean busy) {
        conversationBusy = busy;
        if (sendButton != null) sendButton.setEnabled(!busy);
        if (micButton != null) micButton.setEnabled(!busy);
    }

    private void saveAccessKey() {
        String value = accessKeyInput.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "Paste the Aurum remote access key first", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            BackendCredentialStore.saveAccessToken(this, value);
            accessKeyInput.setText("");
            accessKeyInput.setHint("Access key saved securely");
            refreshDiagnostics();
            Toast.makeText(
                    this,
                    "Aurum access key protected by Android Keystore",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (RuntimeException exception) {
            Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearAccessKey() {
        BackendCredentialStore.clearAccessToken(this);
        accessKeyInput.setText("");
        accessKeyInput.setHint("Paste the 32+ character access key");
        setConnectionState("access key cleared");
        Toast.makeText(this, "Aurum access key cleared", Toast.LENGTH_SHORT).show();
    }

    private String resolveAccessKey() {
        String pasted = accessKeyInput.getText().toString().trim();
        if (!pasted.isEmpty()) {
            try {
                BackendCredentialStore.saveAccessToken(this, pasted);
                accessKeyInput.setText("");
                accessKeyInput.setHint("Access key saved securely");
            } catch (RuntimeException exception) {
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
                return null;
            }
        }
        String stored = BackendCredentialStore.loadAccessToken(this);
        if (stored.isEmpty()) {
            Toast.makeText(this, "Aurum remote access key is required", Toast.LENGTH_LONG).show();
            return null;
        }
        refreshDiagnostics();
        return stored;
    }

    private BackendConfig.Validation validateAndSaveCurrentBackend() {
        BackendConfig.Validation validation = BackendConfig.validate(backendInput.getText().toString());
        if (!validation.valid) {
            Toast.makeText(this, validation.error, Toast.LENGTH_LONG).show();
            return null;
        }
        BackendConfig.saveBaseUrl(this, validation.normalized);
        backendInput.setText(validation.normalized);
        return validation;
    }

    private void setConnectionState(String state) {
        BackendConfig.recordConnectionState(this, state);
        refreshConnectionLabel();
        refreshDiagnostics();
    }

    private void refreshConnectionLabel() {
        if (connectionView == null) return;
        connectionView.setText("Core status: " + BackendConfig.loadConnectionState(this));
    }

    private void refreshVoiceUi() {
        if (voiceStatusView == null) return;
        boolean microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        voiceStatusView.setText(
                "STT: " + VoiceRuntimeState.speechState()
                        + " • microphone " + (microphoneGranted ? "granted" : "not granted")
                        + "\nTTS: " + VoiceRuntimeState.ttsState()
                        + "\nDetected language: " + VoiceRuntimeState.detectedLanguage()
        );

        if (speakButton != null) {
            speakButton.setEnabled(
                    !lastAurumReply.isEmpty()
                            && voiceController != null
                            && voiceController.isTtsReady()
            );
        }
    }

    private void appendTranscript(String speaker, String message) {
        String safeMessage = message == null ? "" : message.trim();
        String current = transcriptView.getText().toString();
        String updated = current + "\n" + speaker + ": " + safeMessage + "\n";
        if (updated.length() > 24_000) updated = updated.substring(updated.length() - 24_000);
        transcriptView.setText(updated);
    }

    private LinearLayout.LayoutParams matchWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams weightedHalf() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private void refreshDiagnostics() {
        if (diagnosticsView == null) return;
        diagnosticsView.setText(DiagnosticsReport.build(this));
    }

    private void copyDiagnostics() {
        String report = DiagnosticsReport.build(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Aurum diagnostics", report));
        Toast.makeText(this, "Aurum diagnostics copied", Toast.LENGTH_SHORT).show();
        diagnosticsView.setText(report);
    }

    private void shareDiagnostics() {
        String report = DiagnosticsReport.build(this);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Aurum Android diagnostics");
        intent.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(intent, "Share Aurum diagnostics"));
        diagnosticsView.setText(report);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
