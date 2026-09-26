package local.qwenimage.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.DragEvent;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private static final int PICK_IMAGE_2 = 1002;
    private static final int NOTIFICATION_PERMISSION = 1003;
    private static final String COMPLETED_CHANNEL = "completed_tasks";
    private static final String SHOW_TASKS = "local.qwenimage.mobile.SHOW_TASKS";
    private static final int INK = Color.rgb(25, 35, 58);
    private static final int SUBTLE = Color.rgb(104, 115, 136);
    private static final int BLUE = Color.rgb(38, 82, 211);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService connectionWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService controlWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService saveWorker = Executors.newSingleThreadExecutor();
    private final ArrayList<DrawTask> pendingTasks = new ArrayList<>();
    private final ArrayList<ResultHistory.Entry> resultHistory = new ArrayList<>();

    private EditText serverField;
    private TextView promptPreview;
    private TextView promptCount;
    private String promptText = "";
    private int promptSelection;
    private EditText stepsField;
    private EditText seedField;
    private Spinner modeField;
    private Spinner sizeField;
    private Spinner encoderField;
    private Button pickButton;
    private Button pickButton2;
    private Button generateButton;
    private Button checkButton;
    private Button saveButton;
    private Button cancelButton;
    private TextView pickedLabel;
    private TextView pickedLabel2;
    private TextView statusLabel;
    private TextView connectionStatus;
    private TextView stageLabel;
    private TextView percentLabel;
    private TextView etaLabel;
    private TextView queueHint;
    private ProgressBar progress;
    private LinearLayout progressCard;
    private LinearLayout pendingContainer;
    private LinearLayout activeContainer;
    private LinearLayout historyContainer;
    private ImageView resultView;
    private ImageView referencePreview;
    private ImageView referencePreview2;
    private LinearLayout referenceBox;
    private LinearLayout referenceBox2;
    private ScrollView createPage;
    private ScrollView tasksPage;
    private ScrollView settingsPage;
    private TextView[] tabs;
    private Uri referenceImage;
    private Uri referenceImage2;
    private byte[] latestImage;
    private ResultHistory.Entry selectedResult;
    private ResultHistory history;
    private boolean savingImage;
    private int resultSelectionVersion;
    private DrawTask activeTask;

    private static final class DrawTask {
        final String id = UUID.randomUUID().toString();
        final String server;
        final String prompt;
        final boolean edit;
        final Uri reference;
        final Uri reference2;
        final int width;
        final int height;
        final int steps;
        final long seed;
        final String encoder;
        volatile String promptId;
        volatile ComfyClient client;
        volatile boolean cancelRequested;

        DrawTask(String server, String prompt, boolean edit, Uri reference, Uri reference2, int width, int height,
                 int steps, long seed, String encoder) {
            this.server = server;
            this.prompt = prompt;
            this.edit = edit;
            this.reference = reference;
            this.reference2 = reference2;
            this.width = width;
            this.height = height;
            this.steps = steps;
            this.seed = seed;
            this.encoder = encoder;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        promptText = getPreferences(MODE_PRIVATE).getString("prompt_draft", "");
        history = new ResultHistory(this);
        createNotificationChannel();
        buildScreen();
        if (SHOW_TASKS.equals(getIntent().getAction())) selectTab(1);
        loadHistory();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (SHOW_TASKS.equals(intent.getAction())) selectTab(1);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(8));
        root.addView(header);

        TextView eyebrow = text("本地 · 局域网绘画", 13, BLUE, true);
        header.addView(eyebrow);
        TextView title = text("躺画", 30, INK, true);
        title.setPadding(0, dp(5), 0, dp(3));
        header.addView(title);
        TextView subtitle = text("躺着画画，电脑负责出图", 14, SUBTLE, false);
        subtitle.setPadding(0, 0, 0, dp(8));
        header.addView(subtitle);

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(tabBar);
        String[] tabNames = {"创作", "任务", "设置"};
        tabs = new TextView[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            TextView tab = text(tabNames[i], 16, SUBTLE, true);
            tab.setGravity(android.view.Gravity.CENTER);
            tabBar.addView(tab, new LinearLayout.LayoutParams(0, dp(46), 1));
            tab.setOnClickListener(v -> selectTab(index));
            tabs[i] = tab;
        }

        createPage = tabPage(root);
        tasksPage = tabPage(root);
        settingsPage = tabPage(root);
        LinearLayout page = tabContent(createPage);
        LinearLayout jobsContent = tabContent(tasksPage);
        LinearLayout settingsContent = tabContent(settingsPage);

        LinearLayout connection = card(settingsContent);
        connection.addView(label("服务地址"));
        serverField = field("例如 http://192.168.1.10:8188", 1);
        serverField.setText(getPreferences(MODE_PRIVATE).getString("server", ""));
        connection.addView(serverField);
        checkButton = button("测试连接", false);
        connection.addView(checkButton);
        checkButton.setOnClickListener(v -> checkServer());
        connectionStatus = text("输入电脑地址后点击测试连接", 13, SUBTLE, false);
        connectionStatus.setPadding(0, dp(8), 0, 0);
        connection.addView(connectionStatus);

        LinearLayout options = card(page);
        options.addView(label("绘图方式"));
        modeField = spinner(new String[]{"文生图", "参考图编辑"});
        options.addView(modeField);
        options.addView(label("描述 / 编辑要求"));
        promptPreview = text("", 15, INK, false);
        promptPreview.setPadding(dp(12), dp(12), dp(12), dp(12));
        promptPreview.setMinHeight(dp(88));
        promptPreview.setMaxLines(4);
        promptPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        promptPreview.setBackground(roundRect(Color.rgb(246, 248, 252), 10));
        options.addView(promptPreview, new LinearLayout.LayoutParams(-1, -2));
        promptPreview.setOnClickListener(v -> openPromptEditor());
        promptCount = text("", 12, SUBTLE, false);
        promptCount.setPadding(0, dp(5), 0, 0);
        options.addView(promptCount);
        updatePromptPreview();

        referenceBox = new LinearLayout(this);
        referenceBox.setOrientation(LinearLayout.VERTICAL);
        options.addView(referenceBox);
        referenceBox.addView(label("参考图 1 · 主图（必选）"));
        pickButton = button("选择参考图 1", false);
        referenceBox.addView(pickButton);
        pickButton.setOnClickListener(v -> openImagePicker(PICK_IMAGE));
        pickedLabel = text("尚未选择图片", 13, SUBTLE, false);
        pickedLabel.setPadding(0, dp(8), 0, dp(4));
        referenceBox.addView(pickedLabel);
        referencePreview = imagePreview(referenceBox);
        Button clearReference = button("清除参考图 1", false);
        referenceBox.addView(clearReference);
        clearReference.setOnClickListener(v -> clearReference(1));

        referenceBox2 = new LinearLayout(this);
        referenceBox2.setOrientation(LinearLayout.VERTICAL);
        options.addView(referenceBox2);
        referenceBox2.addView(label("参考图 2 · 辅助参考（可选）"));
        pickButton2 = button("选择参考图 2", false);
        referenceBox2.addView(pickButton2);
        pickButton2.setOnClickListener(v -> openImagePicker(PICK_IMAGE_2));
        pickedLabel2 = text("尚未选择图片", 13, SUBTLE, false);
        pickedLabel2.setPadding(0, dp(8), 0, dp(4));
        referenceBox2.addView(pickedLabel2);
        referencePreview2 = imagePreview(referenceBox2);
        Button clearReference2 = button("清除参考图 2", false);
        referenceBox2.addView(clearReference2);
        clearReference2.setOnClickListener(v -> clearReference(2));

        options.addView(label("画幅（文生图）"));
        sizeField = spinner(new String[]{"正方形 1024 × 1024", "竖幅 4:5  832 × 1040", "竖幅 9:16  720 × 1280", "横幅 16:9  1280 × 720"});
        options.addView(sizeField);
        options.addView(label("文字编码器"));
        encoderField = spinner(new String[]{"标准 W4A8", "Heretic W4A8"});
        options.addView(encoderField);

        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.HORIZONTAL);
        options.addView(advanced);
        LinearLayout stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        advanced.addView(stepsBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stepsBox.addView(label("步数"));
        stepsField = field("25", 1);
        stepsField.setText("25");
        stepsField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        stepsBox.addView(stepsField);
        LinearLayout seedBox = new LinearLayout(this);
        seedBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams seedParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2);
        seedParams.leftMargin = dp(12);
        advanced.addView(seedBox, seedParams);
        seedBox.addView(label("种子（留空则随机）"));
        seedField = field("随机", 1);
        seedField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        seedBox.addView(seedField);

        modeField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int visibility = position == 1 ? View.VISIBLE : View.GONE;
                referenceBox.setVisibility(visibility);
                referenceBox2.setVisibility(visibility);
                sizeField.setEnabled(position == 0);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        generateButton = button("加入任务队列", true);
        LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, dp(54));
        generateParams.topMargin = dp(18);
        page.addView(generateButton, generateParams);
        generateButton.setOnClickListener(v -> enqueueTask());

        progressCard = card(jobsContent);
        progressCard.setVisibility(View.GONE);
        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setOrientation(LinearLayout.HORIZONTAL);
        progressCard.addView(progressHeader);
        stageLabel = text("排队中", 16, INK, true);
        progressHeader.addView(stageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        percentLabel = text("0%", 18, BLUE, true);
        progressHeader.addView(percentLabel);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(12));
        barParams.topMargin = dp(10);
        progressCard.addView(progress, barParams);
        etaLabel = text("剩余时间：等待采样数据", 13, SUBTLE, false);
        etaLabel.setPadding(0, dp(8), 0, 0);
        progressCard.addView(etaLabel);
        cancelButton = button("取消当前任务", false);
        progressCard.addView(cancelButton);
        cancelButton.setOnClickListener(v -> cancelActive());

        LinearLayout queueCard = card(jobsContent);
        queueCard.addView(label("任务队列"));
        queueHint = text("当前没有任务。待执行任务可长按拖动排序。", 13, SUBTLE, false);
        queueHint.setPadding(0, dp(2), 0, dp(8));
        queueCard.addView(queueHint);
        activeContainer = new LinearLayout(this);
        activeContainer.setOrientation(LinearLayout.VERTICAL);
        queueCard.addView(activeContainer);
        pendingContainer = new LinearLayout(this);
        pendingContainer.setOrientation(LinearLayout.VERTICAL);
        queueCard.addView(pendingContainer);
        pendingContainer.setOnDragListener(this::onPendingDrag);

        LinearLayout resultCard = card(jobsContent);
        resultCard.addView(label("选中的结果"));
        statusLabel = text("等待提交任务", 14, SUBTLE, false);
        statusLabel.setPadding(0, dp(8), 0, dp(12));
        resultCard.addView(statusLabel);
        resultView = new ImageView(this);
        resultView.setAdjustViewBounds(true);
        resultView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultView.setBackground(roundRect(Color.rgb(238, 241, 247), 12));
        resultView.setMinimumHeight(dp(180));
        resultCard.addView(resultView, new LinearLayout.LayoutParams(-1, -2));
        saveButton = button("保存到手机相册", false);
        updateSaveButton();
        resultCard.addView(saveButton);
        saveButton.setOnClickListener(v -> saveImage());

        LinearLayout historyCard = card(jobsContent);
        historyCard.addView(label("任务历史"));
        TextView historyHint = text("成功生成的图片保存在本机，点击记录可查看并保存到相册。", 13, SUBTLE, false);
        historyHint.setPadding(0, 0, 0, dp(8));
        historyCard.addView(historyHint);
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        historyCard.addView(historyContainer);

        setContentView(root);
        selectTab(serverField.getText().length() == 0 ? 2 : 0);
    }

    private ScrollView tabPage(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(246, 247, 251));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return scroll;
    }

    private LinearLayout tabContent(ScrollView scroll) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content);
        return content;
    }

    private void selectTab(int index) {
        ScrollView[] pages = {createPage, tasksPage, settingsPage};
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == index;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(selected ? Color.WHITE : SUBTLE);
            tabs[i].setBackground(roundRect(selected ? BLUE : Color.TRANSPARENT, 12));
        }
    }

    private void updatePromptPreview() {
        promptPreview.setText(promptText.trim().isEmpty()
                ? "点这里输入画面描述或编辑要求…"
                : promptText);
        promptPreview.setTextColor(promptText.trim().isEmpty() ? SUBTLE : INK);
        promptCount.setText(promptText.length() + " 字 · 点摘要可打开独立编辑器");
    }

    private void openPromptEditor() {
        Dialog dialog = new Dialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(12));
        layout.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.addView(toolbar);
        TextView heading = text("编辑提示词", 20, INK, true);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView clear = text("清空", 16, SUBTLE, true);
        clear.setGravity(android.view.Gravity.CENTER);
        clear.setPadding(dp(8), 0, dp(8), 0);
        toolbar.addView(clear, new LinearLayout.LayoutParams(-2, dp(44)));
        TextView done = text("完成", 16, BLUE, true);
        done.setGravity(android.view.Gravity.CENTER);
        done.setPadding(dp(16), 0, dp(8), 0);
        toolbar.addView(done, new LinearLayout.LayoutParams(-2, dp(44)));
        done.setOnClickListener(v -> dialog.dismiss());

        TextView counter = text("", 12, SUBTLE, false);
        counter.setPadding(dp(3), dp(3), 0, dp(10));
        layout.addView(counter);

        EditText editor = field("在这里写完整提示词…", 8);
        editor.setMinLines(1);
        editor.setMaxLines(Integer.MAX_VALUE);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setVerticalScrollBarEnabled(true);
        editor.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        editor.setLineSpacing(dp(3), 1f);
        editor.setText(promptText);
        editor.setSelection(Math.min(promptSelection, editor.length()));
        clear.setOnClickListener(v -> editor.setText(""));
        layout.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        counter.setText(editor.length() + " 字 · 关闭编辑器后保存在本机");
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                counter.setText(s.length() + " 字 · 关闭编辑器后保存在本机");
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(0, dp(9), 0, 0);
        layout.addView(navigation);
        TextView beginning = text("↑ 回到开头", 14, BLUE, true);
        beginning.setGravity(android.view.Gravity.CENTER);
        navigation.addView(beginning, new LinearLayout.LayoutParams(0, dp(42), 1));
        beginning.setOnClickListener(v -> editor.setSelection(0));
        TextView ending = text("↓ 跳到末尾", 14, BLUE, true);
        ending.setGravity(android.view.Gravity.CENTER);
        navigation.addView(ending, new LinearLayout.LayoutParams(0, dp(42), 1));
        ending.setOnClickListener(v -> editor.setSelection(editor.length()));

        dialog.setContentView(layout);
        dialog.setOnDismissListener(ignored -> {
            promptSelection = editor.getSelectionStart();
            promptText = editor.getText().toString();
            getPreferences(MODE_PRIVATE).edit().putString("prompt_draft", promptText).apply();
            updatePromptPreview();
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            window.setLayout(-1, -1);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void checkServer() {
        String url = serverField.getText().toString().trim();
        if (url.isEmpty()) {
            serverField.setError("请输入电脑的 ComfyUI 地址");
            showConnectionStatus("请先输入服务地址", false);
            return;
        }
        checkButton.setEnabled(false);
        checkButton.setText("连接中…");
        showConnectionStatus("正在连接 " + url, null);
        connectionWorker.execute(() -> {
            try {
                new ComfyClient(this, url).checkConnection();
                getPreferences(MODE_PRIVATE).edit().putString("server", url).apply();
                runOnUiThread(() -> {
                    showConnectionStatus("连接成功，ComfyUI 可用", true);
                    finishConnectionCheck();
                    Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showConnectionStatus("连接失败：" + message(e), false);
                    finishConnectionCheck();
                    Toast.makeText(this, "连接失败，请查看按钮下方提示", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showConnectionStatus(String value, Boolean success) {
        connectionStatus.setText(value);
        connectionStatus.setTextColor(success == null ? SUBTLE : success ? Color.rgb(22, 125, 74) : Color.rgb(185, 43, 43));
    }

    private void finishConnectionCheck() {
        checkButton.setText("测试连接");
        checkButton.setEnabled(true);
    }

    private void enqueueTask() {
        String server = serverField.getText().toString().trim();
        if (server.isEmpty()) {
            serverField.setError("请输入电脑的 ComfyUI 地址");
            selectTab(2);
            Toast.makeText(this, "请先在设置中填写服务地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String prompt = promptText.trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "请先填写提示词", Toast.LENGTH_SHORT).show();
            openPromptEditor();
            return;
        }
        boolean edit = modeField.getSelectedItemPosition() == 1;
        if (edit && referenceImage == null) {
            Toast.makeText(this, "请先选择参考图 1", Toast.LENGTH_SHORT).show();
            return;
        }
        int steps;
        long seed;
        try {
            steps = Integer.parseInt(stepsField.getText().toString().trim());
            if (steps < 1 || steps > 100) throw new NumberFormatException();
            String inputSeed = seedField.getText().toString().trim();
            seed = inputSeed.isEmpty() ? (System.currentTimeMillis() & 0x1fffffffffffffL) : Long.parseLong(inputSeed);
            if (seed < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "步数须为 1–100，种子须为非负整数", Toast.LENGTH_LONG).show();
            return;
        }
        int[][] sizes = {{1024, 1024}, {832, 1040}, {720, 1280}, {1280, 720}};
        int[] size = sizes[sizeField.getSelectedItemPosition()];
        String encoder = encoderField.getSelectedItemPosition() == 0
                ? "qwen3vl_8b_w4a8.safetensors" : "qwen3vl_8b_w4a8_heretic.safetensors";
        getPreferences(MODE_PRIVATE).edit().putString("server", server).apply();
        requestNotificationPermission();
        pendingTasks.add(new DrawTask(server, prompt, edit, referenceImage, edit ? referenceImage2 : null,
                size[0], size[1], steps, seed, encoder));
        refreshQueue();
        setStatus("已加入队列 · 等待 " + pendingTasks.size() + " 个任务");
        startNextIfIdle();
        selectTab(1);
    }

    private void startNextIfIdle() {
        if (activeTask != null || pendingTasks.isEmpty()) return;
        DrawTask task = pendingTasks.remove(0);
        activeTask = task;
        progressCard.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        cancelButton.setEnabled(true);
        showStage("准备任务", 0, "正在读取任务参数", -1);
        setStatus("正在执行 · " + taskSummary(task));
        refreshQueue();
        worker.execute(() -> runTask(task));
    }

    private void runTask(DrawTask task) {
        try {
            ComfyClient client = new ComfyClient(this, task.server);
            task.client = client;
            byte[] image = client.generate(task.edit, task.prompt, task.reference, task.reference2,
                    task.width, task.height, task.steps, task.seed, task.encoder,
                    new ComfyClient.StatusListener() {
                        @Override public void update(String value) {
                            runOnUiThread(() -> { if (activeTask == task) setStatus(value); });
                        }
                        @Override public void stage(String name, int percent, String detail, long etaSeconds) {
                            runOnUiThread(() -> {
                                if (activeTask == task && !task.cancelRequested)
                                    showStage(name, percent, detail, etaSeconds);
                            });
                        }
                        @Override public void queued(String promptId) {
                            task.promptId = promptId;
                            if (task.cancelRequested) controlWorker.execute(() -> cancelServerTask(task));
                        }
                        @Override public boolean isCanceled() { return task.cancelRequested; }
                    });
            if (task.cancelRequested) throw new InterruptedException("任务已取消");
            Bitmap bitmap = previewBitmap(image);
            if (bitmap == null) throw new IllegalStateException("返回的文件不是有效图片");
            ResultHistory.Entry entry = null;
            String historyError = null;
            try { entry = history.add(task.prompt, task.seed, task.edit, image); }
            catch (Exception e) { historyError = message(e); }
            ResultHistory.Entry savedEntry = entry;
            String saveError = historyError;
            runOnUiThread(() -> {
                if (activeTask != task) return;
                resultSelectionVersion++;
                latestImage = image;
                selectedResult = savedEntry;
                resultView.setImageBitmap(bitmap);
                updateSaveButton();
                if (savedEntry != null) {
                    resultHistory.add(0, savedEntry);
                    refreshHistory();
                }
                showStage("完成", 100, "图片已生成", 0);
                setStatus(saveError == null ? "完成 · 种子 " + task.seed
                        : "图片已生成，但保存任务历史失败：" + saveError);
                notifyTaskCompleted(task);
                finishTask(task);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (activeTask != task) return;
                setStatus(task.cancelRequested ? "任务已取消" : "生成失败：" + message(e));
                finishTask(task);
            });
        }
    }

    private void finishTask(DrawTask task) {
        if (activeTask != task) return;
        activeTask = null;
        progressCard.setVisibility(View.GONE);
        refreshQueue();
        startNextIfIdle();
    }

    private void cancelActive() {
        DrawTask task = activeTask;
        if (task == null || task.cancelRequested) return;
        task.cancelRequested = true;
        cancelButton.setEnabled(false);
        showStage("正在取消", progress.getProgress(), "通知 ComfyUI 中断", -1);
        if (task.promptId != null) controlWorker.execute(() -> cancelServerTask(task));
    }

    private void cancelServerTask(DrawTask task) {
        try {
            ComfyClient client = task.client;
            String promptId = task.promptId;
            if (client != null && promptId != null) client.cancelJob(promptId);
        } catch (Exception e) {
            runOnUiThread(() -> { if (activeTask == task) setStatus("取消请求失败：" + message(e)); });
        }
    }

    private void refreshQueue() {
        activeContainer.removeAllViews();
        pendingContainer.removeAllViews();
        queueHint.setText(activeTask == null && pendingTasks.isEmpty()
                ? "当前没有任务。待执行任务可长按拖动排序。"
                : "待执行 " + pendingTasks.size() + " 个 · 长按任务拖动排序");
        if (activeTask != null) {
            TextView running = text("正在执行 · " + taskSummary(activeTask), 14, INK, true);
            running.setPadding(0, dp(6), 0, dp(8));
            activeContainer.addView(running);
        }
        for (int i = 0; i < pendingTasks.size(); i++) {
            DrawTask task = pendingTasks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(5), dp(5), dp(5));
            row.setBackground(roundRect(Color.rgb(238, 242, 250), 9));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(6);
            pendingContainer.addView(row, rowParams);
            TextView title = text("☰  " + (i + 1) + ". " + taskSummary(task), 13, INK, false);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = button("移除", false);
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(64), dp(40));
            removeParams.leftMargin = dp(8);
            row.addView(remove, removeParams);
            remove.setOnClickListener(v -> {
                pendingTasks.remove(task);
                refreshQueue();
                setStatus("已移除待执行任务");
            });
            row.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("qwen-task", task.id);
                return row.startDragAndDrop(data, new View.DragShadowBuilder(row), null, 0);
            });
        }
    }

    private String taskSummary(DrawTask task) {
        String shortPrompt = task.prompt.replace('\n', ' ');
        if (shortPrompt.length() > 24) shortPrompt = shortPrompt.substring(0, 24) + "…";
        return (task.edit ? "图生图" : "文生图") + " · " + shortPrompt;
    }

    private boolean onPendingDrag(View view, DragEvent event) {
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
            return event.getClipDescription() != null
                    && event.getClipDescription().hasMimeType("text/plain");
        if (event.getAction() != DragEvent.ACTION_DROP) return true;
        ClipData data = event.getClipData();
        if (data == null || data.getItemCount() == 0) return false;
        String id = data.getItemAt(0).getText().toString();
        int oldIndex = -1;
        for (int i = 0; i < pendingTasks.size(); i++) {
            if (pendingTasks.get(i).id.equals(id)) { oldIndex = i; break; }
        }
        if (oldIndex < 0) return false;
        int insertIndex = pendingTasks.size();
        for (int i = 0; i < pendingContainer.getChildCount(); i++) {
            View row = pendingContainer.getChildAt(i);
            if (event.getY() < row.getTop() + row.getHeight() / 2f) {
                insertIndex = i;
                break;
            }
        }
        DrawTask task = pendingTasks.remove(oldIndex);
        if (oldIndex < insertIndex) insertIndex--;
        pendingTasks.add(Math.max(0, Math.min(insertIndex, pendingTasks.size())), task);
        refreshQueue();
        return true;
    }

    private void showStage(String name, int percent, String detail, long etaSeconds) {
        int value = Math.max(progress.getProgress(), Math.max(0, Math.min(100, percent)));
        progress.setProgress(value);
        stageLabel.setText(name + " · " + detail);
        percentLabel.setText(value == 100 ? "100%" : "约 " + value + "%");
        if (etaSeconds < 0) etaLabel.setText("预计剩余：等待采样数据");
        else etaLabel.setText("预计剩余：约 " + etaSeconds / 60 + " 分 "
                + etaSeconds % 60 + " 秒（按采样步速估算）");
    }

    private void openImagePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == PICK_IMAGE || requestCode == PICK_IMAGE_2)
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some document providers grant access only for the current session.
            }
            if (requestCode == PICK_IMAGE) referenceImage = uri;
            else referenceImage2 = uri;
            TextView label = requestCode == PICK_IMAGE ? pickedLabel : pickedLabel2;
            ImageView preview = requestCode == PICK_IMAGE ? referencePreview : referencePreview2;
            label.setText("正在载入预览…");
            preview.setVisibility(View.GONE);
            previewWorker.execute(() -> loadReferencePreview(uri, requestCode, preview, label));
        }
    }

    private ImageView imagePreview(LinearLayout parent) {
        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(roundRect(Color.rgb(238, 241, 247), 10));
        preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(190));
        params.topMargin = dp(8);
        parent.addView(preview, params);
        return preview;
    }

    private void loadReferencePreview(Uri uri, int requestCode, ImageView preview, TextView label) {
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        double scale = Math.min(1.0, 900.0 / Math.max(width, height));
                        decoder.setTargetSize(Math.max(1, (int) (width * scale)),
                                Math.max(1, (int) (height * scale)));
                    });
            runOnUiThread(() -> {
                if (!uri.equals(requestCode == PICK_IMAGE ? referenceImage : referenceImage2)) return;
                preview.setImageBitmap(bitmap);
                preview.setVisibility(View.VISIBLE);
                label.setText("已选择 · " + bitmap.getWidth() + " × " + bitmap.getHeight() + " 预览");
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (uri.equals(requestCode == PICK_IMAGE ? referenceImage : referenceImage2))
                    label.setText("预览失败：" + message(e));
            });
        }
    }

    private void clearReference(int number) {
        if (number == 1) {
            referenceImage = null;
            referencePreview.setImageDrawable(null);
            referencePreview.setVisibility(View.GONE);
            pickedLabel.setText("尚未选择图片");
        } else {
            referenceImage2 = null;
            referencePreview2.setImageDrawable(null);
            referencePreview2.setVisibility(View.GONE);
            pickedLabel2.setText("尚未选择图片");
        }
    }

    private void saveImage() {
        byte[] image = latestImage;
        if (image == null || savingImage) return;
        savingImage = true;
        updateSaveButton();
        saveWorker.execute(() -> {
            Uri output = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "TangHua_" + UUID.randomUUID() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/躺画");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (output == null) throw new IllegalStateException("相册无法创建文件");
                try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                    if (stream == null) throw new IllegalStateException("相册无法写入文件");
                    stream.write(image);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(output, ready, null, null);
                runOnUiThread(() -> finishSaving("已保存到相册"));
            } catch (Exception e) {
                if (output != null) getContentResolver().delete(output, null, null);
                runOnUiThread(() -> finishSaving("保存失败：" + message(e)));
            }
        });
    }

    private void finishSaving(String feedback) {
        savingImage = false;
        updateSaveButton();
        Toast.makeText(this, feedback, Toast.LENGTH_LONG).show();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(COMPLETED_CHANNEL, "任务完成",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("新图片生成完成时提醒");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || getPreferences(MODE_PRIVATE).getBoolean("notification_permission_requested", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("notification_permission_requested", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
    }

    private void notifyTaskCompleted(DrawTask task) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(SHOW_TASKS);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openTasks = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int icon = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        Notification notification = new Notification.Builder(this, COMPLETED_CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("绘图完成")
                .setContentText(taskSummary(task))
                .setContentIntent(openTasks)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(task.id.hashCode(), notification);
    }

    private void updateSaveButton() {
        boolean enabled = latestImage != null && !savingImage;
        saveButton.setEnabled(enabled);
        saveButton.setText(savingImage ? "正在保存…" : "保存到手机相册");
        saveButton.setTextColor(enabled ? BLUE : SUBTLE);
        saveButton.setBackground(roundRect(enabled ? Color.rgb(232, 237, 250)
                : Color.rgb(230, 232, 237), 12));
    }

    private void loadHistory() {
        previewWorker.execute(() -> {
            ArrayList<ResultHistory.Entry> loaded = history.load();
            runOnUiThread(() -> {
                for (ResultHistory.Entry entry : loaded) {
                    boolean present = false;
                    for (ResultHistory.Entry current : resultHistory) {
                        if (current.id.equals(entry.id)) { present = true; break; }
                    }
                    if (!present) resultHistory.add(entry);
                }
                resultHistory.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                refreshHistory();
                if (!resultHistory.isEmpty() && selectedResult == null && latestImage == null)
                    selectResult(resultHistory.get(0));
            });
        });
    }

    private void refreshHistory() {
        historyContainer.removeAllViews();
        if (resultHistory.isEmpty()) {
            historyContainer.addView(text("暂无已完成的任务", 13, SUBTLE, false));
            return;
        }
        DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(this);
        DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(this);
        for (ResultHistory.Entry entry : resultHistory) {
            String prompt = entry.prompt.replace('\n', ' ');
            if (prompt.length() > 60) prompt = prompt.substring(0, 60) + "…";
            Date date = new Date(entry.createdAt);
            TextView row = text(dateFormat.format(date) + " " + timeFormat.format(date)
                    + " · " + (entry.edit ? "图生图" : "文生图") + " · 种子 " + entry.seed
                    + "\n" + prompt, 13, INK, false);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(roundRect(entry == selectedResult ? Color.rgb(220, 230, 255)
                    : Color.rgb(238, 242, 250), 9));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            historyContainer.addView(row, params);
            row.setOnClickListener(v -> selectResult(entry));
        }
    }

    private void selectResult(ResultHistory.Entry entry) {
        int version = ++resultSelectionVersion;
        selectedResult = entry;
        latestImage = null;
        updateSaveButton();
        resultView.setImageDrawable(null);
        setStatus("正在读取历史图片…");
        refreshHistory();
        previewWorker.execute(() -> {
            try {
                byte[] image = Files.readAllBytes(entry.image.toPath());
                Bitmap bitmap = previewBitmap(image);
                if (bitmap == null) throw new IllegalStateException("图片文件已损坏");
                runOnUiThread(() -> {
                    if (version != resultSelectionVersion) return;
                    latestImage = image;
                    resultView.setImageBitmap(bitmap);
                    updateSaveButton();
                    setStatus("历史结果 · 种子 " + entry.seed);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (version == resultSelectionVersion) setStatus("读取历史图片失败：" + message(e));
                });
            }
        });
    }

    private static Bitmap previewBitmap(byte[] image) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, options);
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1600
                || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(image, 0, image.length, options);
    }

    private void setStatus(String value) { statusLabel.setText(value); }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 14, INK, true);
        view.setPadding(0, dp(12), 0, dp(8));
        return view;
    }

    private EditText field(String hint, int lines) {
        EditText field = new EditText(this);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setHintTextColor(SUBTLE);
        field.setHint(hint);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(roundRect(Color.rgb(246, 248, 252), 10));
        field.setMinLines(lines);
        field.setMaxLines(lines == 1 ? 1 : 10);
        return field;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        spinner.setBackground(roundRect(Color.rgb(246, 248, 252), 10));
        return spinner;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : BLUE);
        button.setBackground(roundRect(primary ? BLUE : Color.rgb(232, 237, 250), 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(18));
        card.setBackground(roundRect(Color.WHITE, 18));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        parent.addView(card, params);
        return card;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        connectionWorker.shutdownNow();
        controlWorker.shutdownNow();
        previewWorker.shutdownNow();
        saveWorker.shutdown();
        super.onDestroy();
    }
}
