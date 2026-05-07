package com.codex.watchai;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS = "watch_ai_config";
    private static final String KEY_SESSIONS = "sessions_json";
    private static final String KEY_CURRENT_SESSION = "current_session_id";
    private static final int BG = Color.rgb(8, 10, 14);
    private static final int PANEL = Color.rgb(23, 27, 34);
    private static final int TEXT = Color.rgb(237, 241, 247);
    private static final int MUTED = Color.rgb(156, 166, 179);
    private static final int ACCENT = Color.rgb(64, 132, 255);
    private static final int INPUT_HIDE_DELAY_MS = 6000;
    private static final int HISTORY_EDGE_PX = 18;

    private final List<Message> messages = new ArrayList<>();
    private final List<ChatSession> sessions = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout container;
    private LinearLayout chatList;
    private LinearLayout inputBar;
    private ScrollView chatScroll;
    private EditText promptInput;
    private TextView statusText;
    private View historyPanel;
    private String currentSessionId;
    private boolean sending;
    private float gestureStartX;
    private float gestureStartY;
    private boolean bottomSwipe;
    private boolean historySwipe;
    private final Runnable hideInputRunnable = new Runnable() {
        @Override
        public void run() {
            hideInputBarIfIdle();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        applyConfigIntent(getIntent());
        loadSessions();
        ensureCurrentSession();
        loadCurrentSessionMessages();
        if (getApiKey().isEmpty()) {
            showSettings();
        } else {
            showChat();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyConfigIntent(intent)) {
            showChat();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleInputRevealGesture(event);
        handleHistoryRevealGesture(event);
        return super.dispatchTouchEvent(event);
    }

    private boolean applyConfigIntent(Intent intent) {
        if (intent == null) return false;
        String baseUrl = intent.getStringExtra("base_url");
        String apiKey = intent.getStringExtra("api_key");
        String model = intent.getStringExtra("model");
        boolean changed = false;
        SharedPreferences.Editor editor = prefs.edit();
        if (baseUrl != null && baseUrl.trim().length() > 0) {
            editor.putString("base_url", baseUrl.trim());
            changed = true;
        }
        if (apiKey != null && apiKey.trim().length() > 0) {
            editor.putString("api_key", apiKey.trim());
            changed = true;
        }
        if (model != null && model.trim().length() > 0) {
            editor.putString("model", model.trim());
            changed = true;
        }
        if (changed) {
            editor.apply();
        }
        return changed;
    }

    private void showChat() {
        root.removeAllViews();
        historyPanel = null;
        container = makeSafeContainer();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(top, new LinearLayout.LayoutParams(-1, dp(24)));

        statusText = label("模型: " + getModel(), 11, TEXT, true);
        statusText.setSingleLine(true);
        statusText.setPadding(dp(7), 0, 0, 0);
        top.addView(statusText, new LinearLayout.LayoutParams(0, -1, 1));
        Button newChat = smallButton("新建");
        newChat.setBackgroundColor(BG);
        newChat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startNewSession();
            }
        });
        top.addView(newChat, new LinearLayout.LayoutParams(dp(38), dp(20)));
        Button settings = smallButton("设");
        settings.setBackgroundColor(BG);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(settings, new LinearLayout.LayoutParams(dp(28), dp(20)));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(false);
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatList, new ScrollView.LayoutParams(-1, -2));
        container.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        inputBar = new LinearLayout(this);
        inputBar.setGravity(Gravity.BOTTOM);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(inputBar, new LinearLayout.LayoutParams(-1, dp(28)));

        promptInput = new EditText(this);
        promptInput.setTextColor(TEXT);
        promptInput.setHintTextColor(MUTED);
        promptInput.setTextSize(11);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(4);
        promptInput.setHint("输入问题");
        promptInput.setSingleLine(false);
        promptInput.setGravity(Gravity.CENTER_VERTICAL);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptInput.setPadding(dp(8), dp(2), dp(8), dp(2));
        promptInput.setBackground(roundedBackground(PANEL, 11));
        inputBar.addView(promptInput, new LinearLayout.LayoutParams(0, dp(24), 1));
        promptInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateInputBarHeight();
            }
        });

        Button send = smallButton("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(8);
        send.setBackground(roundedBackground(ACCENT, 11));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendPrompt();
            }
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(38), dp(24));
        sendLp.setMargins(dp(3), 0, 0, 0);
        inputBar.addView(send, sendLp);
        hideInputBar();
        renderMessages();
        addUserJumpButtons();
        root.requestFocus();
        hideKeyboard(root);
    }

    private void showSettings() {
        root.removeAllViews();
        root.setOnTouchListener(null);
        uiHandler.removeCallbacks(hideInputRunnable);
        inputBar = null;
        historyPanel = null;
        container = makeSafeContainer();

        TextView title = label("API 设置", 11, TEXT, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(title, new LinearLayout.LayoutParams(-1, dp(20)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        container.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        final EditText base = field("API Base URL", getBaseUrl().isEmpty() ? "https://api.deepseek.com/v1" : getBaseUrl(), false);
        final EditText key = field("API Key", getApiKey(), true);
        final EditText model = field("模型名", getModel().isEmpty() ? "deepseek-chat" : getModel(), false);
        form.addView(caption("API Base URL，例如 https://api.deepseek.com/v1"));
        form.addView(base, new LinearLayout.LayoutParams(-1, dp(27)));
        form.addView(caption("API Key，仅保存在本机"));
        form.addView(key, new LinearLayout.LayoutParams(-1, dp(27)));
        form.addView(caption("模型名，可自动获取"));
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(modelRow, new LinearLayout.LayoutParams(-1, dp(27)));
        modelRow.addView(model, new LinearLayout.LayoutParams(0, dp(25), 1));
        Button fetchModels = smallButton("获取");
        fetchModels.setTextColor(Color.WHITE);
        fetchModels.setBackground(roundedBackground(ACCENT, 8));
        LinearLayout.LayoutParams fetchLp = new LinearLayout.LayoutParams(dp(34), dp(25));
        fetchLp.setMargins(dp(4), 0, 0, 0);
        modelRow.addView(fetchModels, fetchLp);

        final TextView note = label("兼容 OpenAI / DeepSeek / OneAPI / 302.AI", 8, MUTED, false);
        note.setPadding(0, dp(3), 0, dp(3));
        form.addView(note);
        fetchModels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchModelsIntoField(base, key, model, note);
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        container.addView(actions, new LinearLayout.LayoutParams(-1, dp(28)));

        Button back = smallButton("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChat();
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(24), 1));
        Button save = smallButton("保存");
        save.setTextColor(Color.WHITE);
        save.setBackground(roundedBackground(ACCENT, 8));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit()
                        .putString("base_url", base.getText().toString().trim())
                        .putString("api_key", key.getText().toString().trim())
                        .putString("model", model.getText().toString().trim())
                        .apply();
                hideKeyboard(key);
                showChat();
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(24), 1));
    }

    private LinearLayout makeSafeContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.post(new Runnable() {
            @Override
            public void run() {
                applyRoundPadding(box);
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    applyRoundPadding(box);
                    return insets;
                }
            });
        }
        return box;
    }

    private void applyRoundPadding(LinearLayout box) {
        int w = Math.max(root.getWidth(), 1);
        int h = Math.max(root.getHeight(), 1);
        int min = Math.min(w, h);
        boolean round = getResources().getConfiguration().isScreenRound();
        int horizontal = round ? Math.max(dp(42), min / 7) : dp(10);
        int vertical = round ? Math.max(dp(14), min / 16) : dp(6);
        WindowInsets insets = android.os.Build.VERSION.SDK_INT >= 23 ? root.getRootWindowInsets() : null;
        if (insets != null) {
            horizontal = Math.max(horizontal, Math.max(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetRight()) + dp(8));
            vertical = Math.max(vertical, Math.max(insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom()) + dp(3));
        }
        box.setPadding(horizontal, vertical, horizontal, vertical);
    }

    private void sendPrompt() {
        if (sending) return;
        String text = promptInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (getApiKey().isEmpty() || getBaseUrl().isEmpty() || getModel().isEmpty()) {
            setStatus("请先完成 API 设置");
            showSettings();
            return;
        }
        hideKeyboard(promptInput);
        promptInput.setText("");
        hideInputBar();
        messages.add(new Message("user", text));
        saveCurrentSession();
        renderMessages();
        setStatus("发送中...");
        sending = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
            String reply;
            try {
                reply = callChatCompletions();
            } catch (Exception e) {
                reply = "请求失败: " + e.getMessage();
            }
            final String finalReply = reply;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sending = false;
                    hideInputBar();
                    messages.add(new Message("assistant", finalReply));
                    trimConversation();
                    saveCurrentSession();
                    requestSessionTitle(currentSessionId);
                    renderMessages();
                    setStatus("模型: " + getModel());
                }
            });
            }
        }).start();
    }

    private void handleInputRevealGesture(MotionEvent event) {
        if (inputBar == null || sending) return;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            gestureStartX = event.getRawX();
            gestureStartY = event.getRawY();
            int height = Math.max(root.getHeight(), 1);
            bottomSwipe = gestureStartY >= height - 5;
        } else if ((event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) && bottomSwipe) {
            float dx = Math.abs(event.getRawX() - gestureStartX);
            float dy = event.getRawY() - gestureStartY;
            if (dy < -dp(16) && dx < dp(130)) {
                showInputBar();
                bottomSwipe = false;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bottomSwipe = false;
        }
    }

    private void handleHistoryRevealGesture(MotionEvent event) {
        if (container == null || sending) return;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            gestureStartX = event.getRawX();
            gestureStartY = event.getRawY();
            int width = Math.max(root.getWidth(), 1);
            historySwipe = gestureStartX >= width - HISTORY_EDGE_PX;
            if (historyPanel != null && historyPanel.getVisibility() == View.VISIBLE && gestureStartX < width / 3) {
                hideHistoryPanel();
            }
        } else if ((event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_UP) && historySwipe) {
            float dx = event.getRawX() - gestureStartX;
            float dy = Math.abs(event.getRawY() - gestureStartY);
            if (dx < -dp(18) && dy < dp(120)) {
                showHistoryPanel();
                historySwipe = false;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            historySwipe = false;
        }
    }

    private void showHistoryPanel() {
        if (root == null) return;
        saveCurrentSession();
        if (historyPanel != null) {
            root.removeView(historyPanel);
            historyPanel = null;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(15, 18, 24));
        panel.setPadding(dp(8), dp(12), dp(8), dp(8));

        TextView title = label("历史会话", 12, TEXT, true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(24)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (sessions.isEmpty()) {
            TextView empty = label("暂无", 10, MUTED, false);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(32)));
        } else {
            for (int i = sessions.size() - 1; i >= 0; i--) {
                final ChatSession session = sessions.get(i);
                TextView item = label(sessionTitle(session), 10, TEXT, false);
                item.setSingleLine(true);
                item.setPadding(dp(6), 0, dp(6), 0);
                item.setBackground(roundedBackground(session.id.equals(currentSessionId) ? Color.rgb(32, 52, 86) : PANEL, 6));
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchSession(session.id);
                    }
                });
                item.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        deleteSession(session.id);
                        return true;
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(30));
                lp.setMargins(0, 0, 0, dp(4));
                list.addView(item, lp);
            }
        }

        int width = Math.max(root.getWidth() * 2 / 3, dp(120));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT);
        root.addView(panel, lp);
        historyPanel = panel;
    }

    private void hideHistoryPanel() {
        if (historyPanel != null) {
            root.removeView(historyPanel);
            historyPanel = null;
        }
    }

    private void showInputBar() {
        if (inputBar == null) return;
        inputBar.setVisibility(View.VISIBLE);
        updateInputBarHeight();
        uiHandler.removeCallbacks(hideInputRunnable);
        uiHandler.postDelayed(hideInputRunnable, INPUT_HIDE_DELAY_MS);
    }

    private void hideInputBar() {
        uiHandler.removeCallbacks(hideInputRunnable);
        if (inputBar != null) {
            inputBar.setVisibility(View.GONE);
        }
    }

    private void hideInputBarIfIdle() {
        if (promptInput != null && (promptInput.hasFocus() || promptInput.getText().toString().trim().length() > 0)) {
            return;
        }
        hideInputBar();
    }

    private void updateInputBarHeight() {
        if (inputBar == null || promptInput == null) return;
        promptInput.post(new Runnable() {
            @Override
            public void run() {
                if (inputBar == null || promptInput == null) return;
                int lines = Math.max(1, promptInput.getLineCount());
                lines = Math.min(4, lines);
                int inputHeight = dp(24 + (lines - 1) * 16);
                int barHeight = inputHeight + dp(4);
                LinearLayout.LayoutParams inputLp = (LinearLayout.LayoutParams) promptInput.getLayoutParams();
                if (inputLp.height != inputHeight) {
                    inputLp.height = inputHeight;
                    promptInput.setLayoutParams(inputLp);
                }
                View parent = (View) inputBar.getParent();
                if (parent != null) {
                    LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) inputBar.getLayoutParams();
                    if (barLp.height != barHeight) {
                        barLp.height = barHeight;
                        inputBar.setLayoutParams(barLp);
                    }
                }
            }
        });
    }

    private void loadSessions() {
        sessions.clear();
        String raw = prefs.getString(KEY_SESSIONS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ChatSession session = new ChatSession(obj.optString("id", ""));
                session.title = obj.optString("title", "");
                session.titleGenerated = obj.optBoolean("title_generated", false);
                JSONArray msgArr = obj.optJSONArray("messages");
                if (msgArr != null) {
                    for (int j = 0; j < msgArr.length(); j++) {
                        JSONObject m = msgArr.getJSONObject(j);
                        String role = m.optString("role", "");
                        String content = m.optString("content", "");
                        if (role.length() > 0 && content.length() > 0) {
                            session.messages.add(new Message(role, content));
                        }
                    }
                }
                if (session.id.length() > 0) sessions.add(session);
            }
        } catch (Exception ignored) {
            sessions.clear();
        }
        currentSessionId = prefs.getString(KEY_CURRENT_SESSION, "");
    }

    private void ensureCurrentSession() {
        if (findSession(currentSessionId) != null) return;
        if (!sessions.isEmpty()) {
            currentSessionId = sessions.get(sessions.size() - 1).id;
        } else {
            ChatSession session = new ChatSession(newSessionId());
            session.title = "新会话";
            sessions.add(session);
            currentSessionId = session.id;
        }
        prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply();
    }

    private void loadCurrentSessionMessages() {
        messages.clear();
        ChatSession session = findSession(currentSessionId);
        if (session != null) {
            messages.addAll(session.messages);
        }
    }

    private void saveCurrentSession() {
        ensureCurrentSession();
        ChatSession session = findSession(currentSessionId);
        if (session == null) return;
        session.messages.clear();
        session.messages.addAll(messages);
        if (!session.titleGenerated) session.title = deriveSessionTitle(session);
        trimStoredSessions();
        saveSessions();
    }

    private void saveSessions() {
        JSONArray arr = new JSONArray();
        try {
            for (ChatSession session : sessions) {
                JSONObject obj = new JSONObject();
                obj.put("id", session.id);
                obj.put("title", session.title);
                obj.put("title_generated", session.titleGenerated);
                JSONArray msgArr = new JSONArray();
                for (Message m : session.messages) {
                    JSONObject msg = new JSONObject();
                    msg.put("role", m.role);
                    msg.put("content", m.content);
                    msgArr.put(msg);
                }
                obj.put("messages", msgArr);
                arr.put(obj);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs.edit()
                .putString(KEY_SESSIONS, arr.toString())
                .putString(KEY_CURRENT_SESSION, currentSessionId)
                .apply();
    }

    private void startNewSession() {
        if (messages.isEmpty()) {
            hideHistoryPanel();
            renderMessages();
            return;
        }
        saveCurrentSession();
        ChatSession session = new ChatSession(newSessionId());
        session.title = "新会话";
        sessions.add(session);
        currentSessionId = session.id;
        messages.clear();
        saveSessions();
        hideHistoryPanel();
        renderMessages();
        setStatus("模型: " + getModel());
    }

    private void switchSession(String id) {
        if (findSession(id) == null) return;
        saveCurrentSession();
        currentSessionId = id;
        prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply();
        loadCurrentSessionMessages();
        hideHistoryPanel();
        renderMessages();
        setStatus("模型: " + getModel());
    }

    private void deleteSession(String id) {
        ChatSession target = findSession(id);
        if (target == null) return;
        sessions.remove(target);
        if (id.equals(currentSessionId)) {
            if (!sessions.isEmpty()) {
                currentSessionId = sessions.get(sessions.size() - 1).id;
            } else {
                ChatSession session = new ChatSession(newSessionId());
                session.title = "新会话";
                sessions.add(session);
                currentSessionId = session.id;
            }
            loadCurrentSessionMessages();
            renderMessages();
        }
        saveSessions();
        setStatus("已删除会话");
        if (historyPanel != null && historyPanel.getVisibility() == View.VISIBLE) {
            showHistoryPanel();
        }
    }

    private ChatSession findSession(String id) {
        if (id == null) return null;
        for (ChatSession session : sessions) {
            if (id.equals(session.id)) return session;
        }
        return null;
    }

    private String newSessionId() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String deriveSessionTitle(ChatSession session) {
        if (session.titleGenerated && session.title != null && session.title.length() > 0) {
            return session.title;
        }
        for (Message m : session.messages) {
            if ("user".equals(m.role) && m.content.trim().length() > 0) {
                return shorten(m.content.trim().replace('\n', ' '), 18);
            }
        }
        return session.title == null || session.title.length() == 0 ? "新会话" : session.title;
    }

    private String sessionTitle(ChatSession session) {
        String title = deriveSessionTitle(session);
        int count = session.messages.size();
        return count > 0 ? title + " (" + count + ")" : title;
    }

    private void trimStoredSessions() {
        while (sessions.size() > 12) {
            ChatSession first = sessions.get(0);
            if (!first.id.equals(currentSessionId)) {
                sessions.remove(0);
            } else if (sessions.size() > 1) {
                sessions.remove(1);
            } else {
                break;
            }
        }
    }

    private void requestSessionTitle(final String sessionId) {
        final ChatSession session = findSession(sessionId);
        if (session == null || session.titleGenerated || session.titleGenerating || session.messages.size() < 2) return;
        if (getApiKey().isEmpty() || getBaseUrl().isEmpty() || getModel().isEmpty()) return;
        session.titleGenerating = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String title = null;
                try {
                    title = callTitleCompletion(session);
                } catch (Exception ignored) {
                    title = null;
                }
                final String finalTitle = cleanTitle(title);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ChatSession current = findSession(sessionId);
                        if (current == null) return;
                        current.titleGenerating = false;
                        if (finalTitle.length() > 0) {
                            current.title = finalTitle;
                            current.titleGenerated = true;
                            saveSessions();
                            if (historyPanel != null && historyPanel.getVisibility() == View.VISIBLE) {
                                showHistoryPanel();
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private String callTitleCompletion(ChatSession session) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", getModel());
        body.put("stream", false);
        body.put("max_tokens", 24);
        JSONArray arr = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "你只负责给对话生成历史标题。用不超过10个中文字概括，只输出标题，不要标点。");
        arr.put(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", titleSourceText(session));
        arr.put(user);
        body.put("messages", arr);

        URL url = new URL(endpointUrl(getBaseUrl()));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
        conn.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String response = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        JSONObject json = new JSONObject(response);
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
    }

    private String titleSourceText(ChatSession session) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(session.messages.size(), 6);
        for (int i = 0; i < count; i++) {
            Message m = session.messages.get(i);
            sb.append("user".equals(m.role) ? "用户: " : "助手: ");
            sb.append(shorten(m.content.replace('\n', ' '), 160));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replace("\n", " ").replace("\r", " ");
        s = s.replace("\"", "").replace("'", "").replace("“", "").replace("”", "");
        s = s.replace("标题:", "").replace("标题：", "").trim();
        while (s.startsWith("-") || s.startsWith("：") || s.startsWith(":")) {
            s = s.substring(1).trim();
        }
        if (s.length() == 0) return "";
        return shorten(s, 16);
    }

    private String callChatCompletions() throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", getModel());
        body.put("stream", false);
        JSONArray arr = new JSONArray();
        int start = Math.max(0, messages.size() - 12);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            JSONObject obj = new JSONObject();
            obj.put("role", m.role);
            obj.put("content", m.content);
            arr.put(obj);
        }
        body.put("messages", arr);

        URL url = new URL(endpointUrl(getBaseUrl()));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
        conn.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String response = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + shorten(response, 220));
        }
        JSONObject json = new JSONObject(response);
        JSONArray choices = json.getJSONArray("choices");
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        return message.optString("content", "").trim();
    }

    private String endpointUrl(String base) {
        String url = base.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) return url;
        return url + "/chat/completions";
    }

    private String modelsUrl(String base) {
        String url = base.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        if (url.endsWith("/models")) return url;
        return url + "/models";
    }

    private void fetchModelsIntoField(final EditText base, final EditText key, final EditText model, final TextView note) {
        final String baseValue = base.getText().toString().trim();
        final String keyValue = key.getText().toString().trim();
        final String preferredModel = model.getText().toString().trim();
        if (baseValue.length() == 0) {
            note.setText("请先填写 Base URL");
            return;
        }
        note.setText("获取中...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(modelsUrl(baseValue));
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(25000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept", "application/json");
                    if (keyValue.length() > 0) {
                        conn.setRequestProperty("Authorization", "Bearer " + keyValue);
                    }
                    int code = conn.getResponseCode();
                    String response = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                    if (code < 200 || code >= 300) {
                        throw new Exception("HTTP " + code + ": " + shorten(response, 80));
                    }
                    JSONObject json = new JSONObject(response);
                    JSONArray data = json.optJSONArray("data");
                    if (data == null || data.length() == 0) {
                        throw new Exception("没有模型列表");
                    }
                    String id = selectModelId(data, preferredModel);
                    if (id.length() == 0) {
                        throw new Exception("没有模型 ID");
                    }
                    final String modelId = id;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            model.setText(modelId);
                            note.setText("已获取: " + modelId);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            note.setText("获取失败: " + shorten(e.getMessage(), 34));
                        }
                    });
                }
            }
        }).start();
    }

    private String selectModelId(JSONArray data, String preferredModel) {
        String first = "";
        String preferred = preferredModel == null ? "" : preferredModel.trim();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            if (id.length() == 0) continue;
            if (first.length() == 0) first = id;
            if (preferred.length() > 0 && (id.equals(preferred) || id.endsWith("/" + preferred))) {
                return id;
            }
        }
        return first;
    }

    private void renderMessages() {
        if (chatList == null) return;
        chatList.removeAllViews();
        for (Message m : messages) {
            TextView bubble = label("", 10, TEXT, false);
            bubble.setText(formatMessageText(m));
            bubble.setTextColor("user".equals(m.role) ? Color.WHITE : TEXT);
            bubble.setBackgroundColor("user".equals(m.role) ? Color.rgb(30, 72, 140) : PANEL);
            bubble.setPadding(dp(6), dp(3), dp(6), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(1), 0, dp(2));
            chatList.addView(bubble, lp);
        }
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                chatScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void addUserJumpButtons() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(0, 0, 0, 0);

        Button up = navButton("↑");
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollToUserMessage(false);
            }
        });
        rail.addView(up, new LinearLayout.LayoutParams(dp(22), dp(24)));

        Button down = navButton("↓");
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollToUserMessage(true);
            }
        });
        LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(dp(22), dp(24));
        downLp.setMargins(0, dp(4), 0, 0);
        rail.addView(down, downLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(24), dp(56), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, 0, dp(6), 0);
        root.addView(rail, lp);
    }

    private Button navButton(String text) {
        Button b = smallButton(text);
        b.setTextSize(13);
        b.setTextColor(TEXT);
        b.setBackground(roundedBackground(Color.argb(210, 23, 27, 34), 12));
        return b;
    }

    private void scrollToUserMessage(final boolean next) {
        if (chatScroll == null || chatList == null || messages.isEmpty()) return;
        chatScroll.post(new Runnable() {
            @Override
            public void run() {
                int scrollY = chatScroll.getScrollY();
                int threshold = dp(20);
                int active = -1;
                int firstBelow = -1;
                int targetIndex = -1;
                for (int i = 0; i < messages.size() && i < chatList.getChildCount(); i++) {
                    if (!"user".equals(messages.get(i).role)) continue;
                    int top = chatList.getChildAt(i).getTop();
                    if (top <= scrollY + threshold) {
                        active = i;
                    } else {
                        firstBelow = i;
                        break;
                    }
                }
                if (next) {
                    targetIndex = firstBelow;
                } else if (active >= 0) {
                    int activeTop = chatList.getChildAt(active).getTop();
                    if (scrollY > activeTop + threshold) {
                        targetIndex = active;
                    } else {
                        for (int i = active - 1; i >= 0; i--) {
                            if ("user".equals(messages.get(i).role)) {
                                targetIndex = i;
                                break;
                            }
                        }
                    }
                } else {
                    for (int i = firstBelow - 1; i >= 0; i--) {
                        if ("user".equals(messages.get(i).role)) {
                            targetIndex = i;
                            break;
                        }
                    }
                }
                if (targetIndex >= 0 && targetIndex < chatList.getChildCount()) {
                    int targetTop = chatList.getChildAt(targetIndex).getTop();
                    chatScroll.smoothScrollTo(0, Math.max(0, targetTop - dp(2)));
                }
            }
        });
    }

    private CharSequence formatMessageText(Message message) {
        String prefix = "user".equals(message.role) ? "我: " : "AI: ";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int prefixStart = out.length();
        out.append(prefix);
        out.setSpan(new StyleSpan(Typeface.BOLD), prefixStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendMarkdownText(out, message.content);
        return out;
    }

    private void appendMarkdownText(SpannableStringBuilder out, String text) {
        String[] lines = (text == null ? "" : text.replace("\r", "")).split("\n", -1);
        boolean inCode = false;
        int codeStart = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (i > 0) out.append('\n');
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = out.length();
                } else {
                    applyCodeSpan(out, codeStart, out.length());
                    inCode = false;
                    codeStart = -1;
                }
                continue;
            }
            if (inCode) {
                out.append(line);
                continue;
            }
            if (isTableSeparatorLine(trimmed)) {
                removeLastNewline(out);
                continue;
            }
            if (isPipeTableRow(trimmed)) {
                appendMarkdownTableRow(out, trimmed);
                if (i + 1 < lines.length && isTableSeparatorLine(lines[i + 1].trim())) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), lineStart(out), out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }
            appendMarkdownLine(out, line);
        }
        if (inCode && codeStart >= 0) {
            applyCodeSpan(out, codeStart, out.length());
        }
    }

    private void appendMarkdownLine(SpannableStringBuilder out, String line) {
        String trimmed = line.trim();
        if (trimmed.length() == 0) return;

        int lineStart = out.length();
        int heading = headingLevel(trimmed);
        if (heading > 0) {
            appendInlineMarkdown(out, trimmed.substring(heading + 1).trim());
            out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(heading == 1 ? 1.18f : 1.08f), lineStart, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        if (trimmed.startsWith(">")) {
            out.append("| ");
            appendInlineMarkdown(out, trimmed.substring(1).trim());
            out.setSpan(new ForegroundColorSpan(MUTED), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        if (isUnorderedListLine(trimmed)) {
            out.append("• ");
            appendInlineMarkdown(out, trimmed.substring(2).trim());
            out.setSpan(new LeadingMarginSpan.Standard(0, dp(10)), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        int numberEnd = numberedListMarkerEnd(trimmed);
        if (numberEnd > 0) {
            out.append(trimmed.substring(0, numberEnd));
            appendInlineMarkdown(out, trimmed.substring(numberEnd).trim());
            out.setSpan(new LeadingMarginSpan.Standard(0, dp(12)), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        appendInlineMarkdown(out, line);
    }

    private int headingLevel(String trimmed) {
        int count = 0;
        while (count < trimmed.length() && trimmed.charAt(count) == '#') count++;
        if (count > 0 && count <= 4 && count < trimmed.length() && trimmed.charAt(count) == ' ') return count;
        return 0;
    }

    private boolean isUnorderedListLine(String trimmed) {
        if (trimmed.length() < 3) return false;
        char first = trimmed.charAt(0);
        return (first == '-' || first == '*' || first == '+') && trimmed.charAt(1) == ' ';
    }

    private int numberedListMarkerEnd(String trimmed) {
        int i = 0;
        while (i < trimmed.length() && Character.isDigit(trimmed.charAt(i))) i++;
        if (i == 0 || i + 1 >= trimmed.length()) return -1;
        if (trimmed.charAt(i) == '.' && trimmed.charAt(i + 1) == ' ') return i + 2;
        return -1;
    }

    private boolean isPipeTableRow(String trimmed) {
        return trimmed.indexOf('|') >= 0 && splitPipeCells(trimmed).size() >= 2;
    }

    private boolean isTableSeparatorLine(String trimmed) {
        if (!isPipeTableRow(trimmed)) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c != '|' && c != '-' && c != ':' && !Character.isWhitespace(c)) return false;
        }
        return trimmed.indexOf('-') >= 0;
    }

    private void appendMarkdownTableRow(SpannableStringBuilder out, String trimmed) {
        List<String> cells = splitPipeCells(trimmed);
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append("  ");
            appendInlineMarkdown(out, cells.get(i));
        }
    }

    private List<String> splitPipeCells(String row) {
        String s = row.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        List<String> cells = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '|') {
                String cell = s.substring(start, i).trim();
                if (cell.length() > 0) cells.add(cell);
                start = i + 1;
            }
        }
        return cells;
    }

    private void removeLastNewline(SpannableStringBuilder out) {
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == '\n') out.delete(len - 1, len);
    }

    private int lineStart(SpannableStringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            if (out.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    private void appendInlineMarkdown(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            int marker = findNextInlineMarker(text, i);
            if (marker < 0) {
                appendMath(out, text.substring(i));
                return;
            }
            if (marker > i) {
                appendMath(out, text.substring(i, marker));
            }
            if (text.startsWith("**", marker)) {
                int close = text.indexOf("**", marker + 2);
                if (close > marker + 2) {
                    int start = out.length();
                    appendMath(out, text.substring(marker + 2, close));
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }
            if (text.charAt(marker) == '`') {
                int close = text.indexOf('`', marker + 1);
                if (close > marker + 1) {
                    int start = out.length();
                    out.append(text.substring(marker + 1, close));
                    applyCodeSpan(out, start, out.length());
                    i = close + 1;
                    continue;
                }
            }
            if (text.charAt(marker) == '*') {
                int close = text.indexOf('*', marker + 1);
                if (close > marker + 1) {
                    int start = out.length();
                    appendMath(out, text.substring(marker + 1, close));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            appendMath(out, text.substring(marker, marker + 1));
            i = marker + 1;
        }
    }

    private int findNextInlineMarker(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') return i;
            if (c == '*' && text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2) return i;
            if (c == '*' && isItalicMarker(text, i) && text.indexOf('*', i + 1) > i + 1) return i;
        }
        return -1;
    }

    private boolean isItalicMarker(String text, int index) {
        if (index + 1 >= text.length() || Character.isWhitespace(text.charAt(index + 1))) return false;
        if (index > 0 && Character.isLetterOrDigit(text.charAt(index - 1))) return false;
        return true;
    }

    private void appendMath(SpannableStringBuilder out, String text) {
        out.append(renderMathText(normalizeMathText(text)));
    }

    private void applyCodeSpan(SpannableStringBuilder out, int start, int end) {
        if (start < 0 || end <= start) return;
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(Color.rgb(16, 19, 24)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(Color.rgb(218, 226, 236)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String normalizeMathText(String text) {
        String s = text == null ? "" : text;
        s = s.replace("\\(", "").replace("\\)", "");
        s = s.replace("\\[", "").replace("\\]", "");
        s = s.replace("$$", "").replace("$", "");
        s = replaceMathEnvironments(s);
        s = s.replace("\\\\", "\n");
        s = s.replace("\\left", "").replace("\\right", "");
        s = s.replace("&", " ");
        s = replaceOneArgCommand(s, "\\begin", "", "");
        s = replaceOneArgCommand(s, "\\end", "", "");
        s = replaceOneArgCommand(s, "begin", "", "");
        s = replaceOneArgCommand(s, "end", "", "");
        s = cleanupMathEnvironmentTokens(s);
        s = replaceBlackboard(s);
        s = replacePowerSet(s);
        s = unwrapCommand(s, "\\text");
        s = unwrapCommand(s, "\\textrm");
        s = unwrapCommand(s, "\\mathrm");
        s = unwrapCommand(s, "\\mathbf");
        s = unwrapCommand(s, "\\mathit");
        s = unwrapCommand(s, "\\mathsf");
        s = unwrapCommand(s, "\\mathtt");
        s = unwrapCommand(s, "\\mathcal");
        s = unwrapCommand(s, "\\operatorname");
        s = replaceTwoArgCommand(s, "\\binom", "C(", ", ", ")");
        s = replaceFractions(s, "\\frac");
        s = replaceFractions(s, "\\dfrac");
        s = replaceFractions(s, "\\tfrac");
        s = replaceSqrt(s);
        s = replaceCombiningCommand(s, "\\overline", "\u0305");
        s = replaceCombiningCommand(s, "\\bar", "\u0304");
        s = replaceCombiningCommand(s, "\\hat", "\u0302");
        s = replaceCombiningCommand(s, "\\tilde", "\u0303");
        s = replaceCombiningCommand(s, "\\vec", "\u20d7");
        s = replaceCommands(s, new String[][]{
                {"\\arccos", "arccos"}, {"\\arcsin", "arcsin"}, {"\\arctan", "arctan"},
                {"\\cosh", "cosh"}, {"\\sinh", "sinh"}, {"\\tanh", "tanh"},
                {"\\sin", "sin"}, {"\\cos", "cos"}, {"\\tan", "tan"}, {"\\cot", "cot"}, {"\\sec", "sec"}, {"\\csc", "csc"},
                {"\\log", "log"}, {"\\ln", "ln"}, {"\\lg", "lg"}, {"\\exp", "exp"},
                {"\\limsup", "lim sup"}, {"\\liminf", "lim inf"}, {"\\lim", "lim"},
                {"\\max", "max"}, {"\\min", "min"}, {"\\sup", "sup"}, {"\\inf", "inf"},
                {"\\argmax", "arg max"}, {"\\argmin", "arg min"}, {"\\deg", "deg"}, {"\\det", "det"}, {"\\dim", "dim"},
                {"\\gcd", "gcd"}, {"\\ker", "ker"}, {"\\hom", "hom"}, {"\\rank", "rank"}, {"\\mod", "mod"}
        });
        s = replaceCommands(s, new String[][]{
                {"\\not\\models", "⊭"}, {"\\not\\vdash", "⊬"}, {"\\nmodels", "⊭"}, {"\\nvdash", "⊬"},
                {"\\models", "⊨"}, {"\\vDash", "⊨"}, {"\\vdash", "⊢"}, {"\\dashv", "⊣"},
                {"\\top", "⊤"}, {"\\bot", "⊥"}, {"\\True", "⊤"}, {"\\False", "⊥"},
                {"\\iff", "⇔"}, {"\\implies", "⇒"}, {"\\impliedby", "⇐"},
                {"\\Longleftrightarrow", "⇔"}, {"\\longleftrightarrow", "⟷"}, {"\\Leftrightarrow", "⇔"}, {"\\leftrightarrow", "↔"},
                {"\\Longrightarrow", "⇒"}, {"\\longrightarrow", "⟶"}, {"\\Rightarrow", "⇒"}, {"\\rightarrow", "→"},
                {"\\Longleftarrow", "⇐"}, {"\\longleftarrow", "⟵"}, {"\\Leftarrow", "⇐"}, {"\\leftarrow", "←"},
                {"\\hookrightarrow", "↪"}, {"\\twoheadrightarrow", "↠"}, {"\\mapsto", "↦"}, {"\\to", "→"}, {"\\gets", "←"}, {"\\uparrow", "↑"}, {"\\downarrow", "↓"},
                {"\\times", "×"}, {"\\cdots", "⋯"}, {"\\ldots", "…"}, {"\\dots", "…"}, {"\\cdot", "·"}, {"\\div", "÷"},
                {"\\leqslant", "≤"}, {"\\geqslant", "≥"}, {"\\leqq", "≦"}, {"\\geqq", "≧"}, {"\\leq", "≤"}, {"\\geq", "≥"}, {"\\le", "≤"}, {"\\ge", "≥"},
                {"\\neq", "≠"}, {"\\ne", "≠"}, {"\\equiv", "≡"}, {"\\cong", "≅"}, {"\\simeq", "≃"}, {"\\approx", "≈"}, {"\\propto", "∝"}, {"\\asymp", "≍"}, {"\\sim", "∼"},
                {"\\preceq", "⪯"}, {"\\succeq", "⪰"}, {"\\prec", "≺"}, {"\\succ", "≻"},
                {"\\ll", "≪"}, {"\\gg", "≫"}, {"\\perp", "⊥"}, {"\\parallel", "∥"}, {"\\angle", "∠"}, {"\\triangle", "△"},
                {"\\infty", "∞"}, {"\\sum", "∑"}, {"\\prod", "∏"}, {"\\coprod", "∐"}, {"\\iint", "∬"}, {"\\iiint", "∭"}, {"\\oint", "∮"}, {"\\int", "∫"},
                {"\\partial", "∂"}, {"\\nabla", "∇"}, {"\\pm", "±"}, {"\\mp", "∓"}, {"\\therefore", "∴"}, {"\\because", "∵"},
                {"\\not\\in", "∉"}, {"\\notin", "∉"}, {"\\in", "∈"}, {"\\ni", "∋"}, {"\\emptyset", "∅"}, {"\\varnothing", "∅"}, {"\\empty", "∅"},
                {"\\not\\subseteq", "⊈"}, {"\\not\\supseteq", "⊉"}, {"\\subsetneq", "⊊"}, {"\\supsetneq", "⊋"},
                {"\\subseteq", "⊆"}, {"\\supseteq", "⊇"}, {"\\subset", "⊂"}, {"\\supset", "⊃"}, {"\\nsubseteq", "⊈"}, {"\\nsupseteq", "⊉"},
                {"\\cup", "∪"}, {"\\cap", "∩"}, {"\\smallsetminus", "∖"}, {"\\setminus", "∖"}, {"\\forall", "∀"}, {"\\exists", "∃"}, {"\\nexists", "∄"},
                {"\\land", "∧"}, {"\\wedge", "∧"}, {"\\lor", "∨"}, {"\\vee", "∨"}, {"\\neg", "¬"}, {"\\lnot", "¬"},
                {"\\bigoplus", "⊕"}, {"\\oplus", "⊕"}, {"\\otimes", "⊗"}, {"\\odot", "⊙"}, {"\\star", "⋆"}, {"\\circ", "∘"},
                {"\\lfloor", "⌊"}, {"\\rfloor", "⌋"}, {"\\lceil", "⌈"}, {"\\rceil", "⌉"}, {"\\langle", "⟨"}, {"\\rangle", "⟩"},
                {"\\N", "ℕ"}, {"\\Z", "ℤ"}, {"\\Q", "ℚ"}, {"\\R", "ℝ"}, {"\\C", "ℂ"},
                {"\\{", "{"}, {"\\}", "}"}, {"\\vert", "|"}, {"\\mid", "|"}, {"\\colon", ":"}
        });
        s = replaceCommands(s, new String[][]{
                {"\\varepsilon", "ε"}, {"\\epsilon", "ε"}, {"\\vartheta", "ϑ"}, {"\\theta", "θ"}, {"\\varpi", "ϖ"},
                {"\\varrho", "ϱ"}, {"\\varsigma", "ς"}, {"\\sigma", "σ"}, {"\\varphi", "φ"}, {"\\phi", "φ"},
                {"\\alpha", "α"}, {"\\beta", "β"}, {"\\gamma", "γ"}, {"\\delta", "δ"}, {"\\zeta", "ζ"}, {"\\eta", "η"},
                {"\\iota", "ι"}, {"\\kappa", "κ"}, {"\\lambda", "λ"}, {"\\mu", "μ"}, {"\\nu", "ν"}, {"\\xi", "ξ"},
                {"\\pi", "π"}, {"\\rho", "ρ"}, {"\\tau", "τ"}, {"\\upsilon", "υ"}, {"\\chi", "χ"}, {"\\psi", "ψ"}, {"\\omega", "ω"},
                {"\\Gamma", "Γ"}, {"\\Delta", "Δ"}, {"\\Theta", "Θ"}, {"\\Lambda", "Λ"}, {"\\Xi", "Ξ"}, {"\\Pi", "Π"},
                {"\\Sigma", "Σ"}, {"\\Upsilon", "Υ"}, {"\\Phi", "Φ"}, {"\\Psi", "Ψ"}, {"\\Omega", "Ω"}
        });
        s = replaceAsciiDiscreteMath(s);
        s = s.replace("\\quad", " ").replace("\\qquad", "  ").replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "").replace("\\ ", " ");
        s = cleanupMathEnvironmentTokens(s);
        return s;
    }

    private String replaceCommands(String s, String[][] pairs) {
        for (String[] pair : pairs) {
            s = s.replace(pair[0], pair[1]);
        }
        return s;
    }

    private String replaceMathEnvironments(String s) {
        String[] envs = new String[]{"pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "matrix", "smallmatrix", "cases", "array"};
        for (String env : envs) {
            s = replaceMathEnvironment(s, env, true);
            s = replaceMathEnvironment(s, env, false);
        }
        return s;
    }

    private String replaceMathEnvironment(String s, String env, boolean slashed) {
        String begin = (slashed ? "\\begin{" : "begin{") + env + "}";
        String end = (slashed ? "\\end{" : "end{") + env + "}";
        int index = s.indexOf(begin);
        while (index >= 0) {
            int bodyStart = index + begin.length();
            if ("array".equals(env) && bodyStart < s.length() && s.charAt(bodyStart) == '{') {
                int specEnd = findMatchingBrace(s, bodyStart);
                if (specEnd > bodyStart) bodyStart = specEnd + 1;
            }
            int bodyEnd = s.indexOf(end, bodyStart);
            if (bodyEnd < 0) break;
            String body = s.substring(bodyStart, bodyEnd);
            String replacement = formatMathEnvironment(env, body);
            s = s.substring(0, index) + replacement + s.substring(bodyEnd + end.length());
            index = s.indexOf(begin, index + replacement.length());
        }
        return s;
    }

    private String formatMathEnvironment(String env, String body) {
        String normalized = body.replace("\\\\", "\n").replace("\\cr", "\n").replace("&", "  ").trim();
        while (normalized.contains("\n\n")) normalized = normalized.replace("\n\n", "\n");
        if (normalized.length() == 0) return "";
        String open = "[";
        String close = "]";
        if ("pmatrix".equals(env) || "smallmatrix".equals(env)) {
            open = "(";
            close = ")";
        } else if ("Bmatrix".equals(env)) {
            open = "{";
            close = "}";
        } else if ("vmatrix".equals(env) || "Vmatrix".equals(env)) {
            open = "|";
            close = "|";
        } else if ("cases".equals(env)) {
            open = "{";
            close = "";
        }
        return open + "\n" + normalized + (close.length() > 0 ? "\n" + close : "");
    }

    private String cleanupMathEnvironmentTokens(String s) {
        String[] lines = s.replace("\r", "").split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (isBareMathEnvironmentToken(line.trim())) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private boolean isBareMathEnvironmentToken(String s) {
        return "pmatrix".equals(s) || "bmatrix".equals(s) || "Bmatrix".equals(s)
                || "vmatrix".equals(s) || "Vmatrix".equals(s) || "matrix".equals(s)
                || "smallmatrix".equals(s) || "cases".equals(s) || "array".equals(s);
    }

    private String replacePowerSet(String s) {
        s = s.replace("\\mathcal{P}", "℘");
        s = s.replace("\\mathscr{P}", "℘");
        s = s.replace("\\cal{P}", "℘");
        return s;
    }

    private String replaceAsciiDiscreteMath(String s) {
        s = s.replace("<=>", "⇔").replace("<->", "↔");
        s = s.replace("=>", "⇒").replace("->", "→").replace("<-", "←");
        s = s.replace("<=", "≤").replace(">=", "≥").replace("!=", "≠");
        return s;
    }

    private String unwrapCommand(String s, String command) {
        return replaceOneArgCommand(s, command, "", "");
    }

    private String replaceBlackboard(String s) {
        String needle = "\\mathbb{";
        int index = s.indexOf(needle);
        while (index >= 0) {
            int argStart = index + "\\mathbb".length();
            int argEnd = findMatchingBrace(s, argStart);
            if (argEnd < 0) break;
            String replacement = blackboardText(s.substring(argStart + 1, argEnd));
            s = s.substring(0, index) + replacement + s.substring(argEnd + 1);
            index = s.indexOf(needle, index + replacement.length());
        }
        return s;
    }

    private String blackboardText(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case 'A': out.append("𝔸"); break;
                case 'B': out.append("𝔹"); break;
                case 'C': out.append("ℂ"); break;
                case 'D': out.append("𝔻"); break;
                case 'E': out.append("𝔼"); break;
                case 'F': out.append("𝔽"); break;
                case 'G': out.append("𝔾"); break;
                case 'H': out.append("ℍ"); break;
                case 'I': out.append("𝕀"); break;
                case 'J': out.append("𝕁"); break;
                case 'K': out.append("𝕂"); break;
                case 'L': out.append("𝕃"); break;
                case 'M': out.append("𝕄"); break;
                case 'N': out.append("ℕ"); break;
                case 'O': out.append("𝕆"); break;
                case 'P': out.append("ℙ"); break;
                case 'Q': out.append("ℚ"); break;
                case 'R': out.append("ℝ"); break;
                case 'S': out.append("𝕊"); break;
                case 'T': out.append("𝕋"); break;
                case 'U': out.append("𝕌"); break;
                case 'V': out.append("𝕍"); break;
                case 'W': out.append("𝕎"); break;
                case 'X': out.append("𝕏"); break;
                case 'Y': out.append("𝕐"); break;
                case 'Z': out.append("ℤ"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private String replaceFractions(String s, String command) {
        String needle = command + "{";
        int index = s.indexOf(needle);
        while (index >= 0) {
            int numeratorStart = index + command.length();
            int numeratorEnd = findMatchingBrace(s, numeratorStart);
            if (numeratorEnd < 0 || numeratorEnd + 1 >= s.length() || s.charAt(numeratorEnd + 1) != '{') break;
            int denominatorStart = numeratorEnd + 1;
            int denominatorEnd = findMatchingBrace(s, denominatorStart);
            if (denominatorEnd < 0) break;
            String numerator = s.substring(numeratorStart + 1, numeratorEnd);
            String denominator = s.substring(denominatorStart + 1, denominatorEnd);
            String replacement = "(" + numerator + ")/(" + denominator + ")";
            s = s.substring(0, index) + replacement + s.substring(denominatorEnd + 1);
            index = s.indexOf(needle, index + replacement.length());
        }
        return s;
    }

    private String replaceTwoArgCommand(String s, String command, String before, String between, String after) {
        String needle = command + "{";
        int index = s.indexOf(needle);
        while (index >= 0) {
            int firstStart = index + command.length();
            int firstEnd = findMatchingBrace(s, firstStart);
            if (firstEnd < 0 || firstEnd + 1 >= s.length() || s.charAt(firstEnd + 1) != '{') break;
            int secondStart = firstEnd + 1;
            int secondEnd = findMatchingBrace(s, secondStart);
            if (secondEnd < 0) break;
            String first = s.substring(firstStart + 1, firstEnd);
            String second = s.substring(secondStart + 1, secondEnd);
            String replacement = before + first + between + second + after;
            s = s.substring(0, index) + replacement + s.substring(secondEnd + 1);
            index = s.indexOf(needle, index + replacement.length());
        }
        return s;
    }

    private String replaceSqrt(String s) {
        String command = "\\sqrt";
        int index = s.indexOf(command);
        while (index >= 0) {
            int cursor = index + command.length();
            String degree = "";
            if (cursor < s.length() && s.charAt(cursor) == '[') {
                int close = s.indexOf(']', cursor);
                if (close > cursor) {
                    degree = s.substring(cursor + 1, close);
                    cursor = close + 1;
                }
            }
            if (cursor >= s.length() || s.charAt(cursor) != '{') {
                index = s.indexOf(command, index + command.length());
                continue;
            }
            int argEnd = findMatchingBrace(s, cursor);
            if (argEnd < 0) break;
            String arg = s.substring(cursor + 1, argEnd);
            String replacement = (degree.length() > 0 ? degree : "") + "√(" + arg + ")";
            s = s.substring(0, index) + replacement + s.substring(argEnd + 1);
            index = s.indexOf(command, index + replacement.length());
        }
        return s;
    }

    private String replaceCombiningCommand(String s, String command, String mark) {
        String needle = command + "{";
        int index = s.indexOf(needle);
        while (index >= 0) {
            int argStart = index + command.length();
            int argEnd = findMatchingBrace(s, argStart);
            if (argEnd < 0) break;
            String replacement = withCombiningMark(s.substring(argStart + 1, argEnd), mark);
            s = s.substring(0, index) + replacement + s.substring(argEnd + 1);
            index = s.indexOf(needle, index + replacement.length());
        }
        return s;
    }

    private String withCombiningMark(String text, String mark) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (!Character.isWhitespace(c)) out.append(mark);
        }
        return out.toString();
    }

    private String replaceOneArgCommand(String s, String command, String before, String after) {
        String needle = command + "{";
        int index = s.indexOf(needle);
        while (index >= 0) {
            int argStart = index + command.length();
            int argEnd = findMatchingBrace(s, argStart);
            if (argEnd < 0) break;
            String arg = s.substring(argStart + 1, argEnd);
            String replacement = before + arg + after;
            s = s.substring(0, index) + replacement + s.substring(argEnd + 1);
            index = s.indexOf(needle, index + replacement.length());
        }
        return s;
    }

    private int findMatchingBrace(String s, int openIndex) {
        if (openIndex < 0 || openIndex >= s.length() || s.charAt(openIndex) != '{') return -1;
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private CharSequence renderMathText(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '^' || c == '_') && i + 1 < text.length()) {
                boolean superscript = c == '^';
                int tokenStart = i + 1;
                String token;
                int tokenEnd;
                if (text.charAt(tokenStart) == '{') {
                    int close = findMatchingBrace(text, tokenStart);
                    if (close < 0) {
                        out.append(c);
                        continue;
                    }
                    token = text.substring(tokenStart + 1, close);
                    tokenEnd = close;
                } else {
                    token = String.valueOf(text.charAt(tokenStart));
                    tokenEnd = tokenStart;
                }
                int spanStart = out.length();
                out.append(token);
                int spanEnd = out.length();
                out.setSpan(superscript ? new SuperscriptSpan() : new SubscriptSpan(), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.72f), spanStart, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = tokenEnd;
            } else {
                out.append(c);
            }
        }
        return out;
    }

    private TextView caption(String text) {
        TextView tv = label(text, 7, MUTED, false);
        tv.setPadding(0, dp(4), 0, dp(1));
        return tv;
    }

    private EditText field(String hint, String value, boolean password) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(9);
        edit.setSingleLine(true);
        edit.setPadding(dp(5), 0, dp(5), 0);
        edit.setBackgroundColor(PANEL);
        edit.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return edit;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(9);
        b.setTextColor(TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setIncludeFontPadding(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setBackgroundColor(PANEL);
        return b;
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String getBaseUrl() {
        return prefs.getString("base_url", "https://api.deepseek.com/v1");
    }

    private String getApiKey() {
        return prefs.getString("api_key", "");
    }

    private String getModel() {
        return prefs.getString("model", "deepseek-chat");
    }

    private void trimConversation() {
        while (messages.size() > 16) {
            messages.remove(0);
        }
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class Message {
        final String role;
        final String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class ChatSession {
        final String id;
        String title;
        boolean titleGenerated;
        boolean titleGenerating;
        final List<Message> messages = new ArrayList<>();

        ChatSession(String id) {
            this.id = id;
        }
    }
}
