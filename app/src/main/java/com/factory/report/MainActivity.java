package com.factory.report;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "AppPrefs";
    private static final String DEFAULT_EMAIL = "kuangming.fan@tci.com.tw";
    private static final String KEY_TARGET_INDEX = "state_target_index";
    private static final String KEY_PENDING_PHOTO = "state_pending_photo";

    // Colors — Industrial Dark Slate Palette
    private static final int COL_BG_APP = Color.parseColor("#0B1120");
    private static final int COL_BG_CARD = Color.parseColor("#151F32");
    private static final int COL_BG_ELEVATED = Color.parseColor("#1E293B");
    private static final int COL_BORDER = Color.parseColor("#263449");
    private static final int COL_CYAN = Color.parseColor("#38BDF8");
    private static final int COL_CYAN_TINT = Color.parseColor("#0C4A6E");
    private static final int COL_EMERALD = Color.parseColor("#10B981");
    private static final int COL_EMERALD_DARK = Color.parseColor("#059669");
    private static final int COL_BLUE = Color.parseColor("#0284C7");
    private static final int COL_AMBER = Color.parseColor("#F59E0B");
    private static final int COL_RED = Color.parseColor("#DC2626");
    private static final int COL_TEXT_PRIMARY = Color.parseColor("#F8FAFC");
    private static final int COL_TEXT_MUTED = Color.parseColor("#94A3B8");

    // Storage & Threading
    private File folder;
    private File savedFolder;
    private File logFile;
    private final File[] photoFiles = new File[6];
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // User Preferences
    private SharedPreferences prefs;
    private String emailAddress = DEFAULT_EMAIL;
    private String selectedChemical = "";
    private String imgQuality = "高畫質";
    private int autoDeleteDays = 7;
    private boolean autoBackup = false;

    // Camera capture state (persisted across process death)
    private int targetIndex = -1;
    private String pendingPhotoPath = null;

    // Checklist metadata
    private final String[] baseDescriptions = {
            "貨車車牌", "車輪檔", "@@\n藥液出口",
            "@@\n藥桶入口", "貨車\n接地線", "司機\n防護裝備"
    };
    private final String[] slotIcons = {"🚛", "🛑", "🚰", "🛢️", "⚡", "🦺"};
    private final String[] chemOptions = {"氯化銅", "液鹼", "氯化鐵", "氯化亞鐵", "硫酸", "酸化助劑", "+ 自訂"};

    // UI References
    private TextView txtSubtitle;
    private LinearLayout cardChem;
    private TextView lblChemTitle;
    private TextView txtProgressLabel;
    private TextView txtProgressBadge;
    private ProgressBar progressBar;
    private LinearLayout layChemChips;
    private final List<TextView> chemChipViews = new ArrayList<>();
    private final SlotCardHolder[] slotHolders = new SlotCardHolder[6];
    private Button btnSend;

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private int pendingSlotAfterPermission = -1;

    private static class SlotCardHolder {
        FrameLayout root;
        ImageView imgThumb;
        View scrim;
        LinearLayout emptyBox;
        TextView txtEmptyIcon;
        TextView txtEmptyLabel;
        TextView txtEmptyHint;
        TextView txtSlotNum;
        TextView txtCheckBadge;
        TextView txtOverlayLabel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        emailAddress = prefs.getString("TargetEmail", DEFAULT_EMAIL);
        imgQuality = prefs.getString("ImgQuality", "高畫質");
        autoDeleteDays = prefs.getInt("AutoDeleteDays", 7);
        autoBackup = prefs.getBoolean("AutoBackup", false);

        folder = new File(getExternalFilesDir(null), "DebugPhotos");
        if (!folder.exists()) folder.mkdirs();

        savedFolder = new File(getExternalFilesDir(null), "SavedReports");
        if (!savedFolder.exists()) savedFolder.mkdirs();

        logFile = new File(folder, "debug_log.txt");

        restorePhotoState(savedInstanceState);
        // Only restore previously selected chemical if there are in-progress photos;
        // otherwise require explicit selection to avoid accidental default chemical.
        if (countCapturedPhotos() > 0 || !TextUtils.isEmpty(pendingPhotoPath)) {
            selectedChemical = prefs.getString("SelectedChemical", "");
        } else {
            selectedChemical = "";
        }
        registerLaunchers();

        writeLog("程式啟動 v2.1｜自動備份: " + (autoBackup ? "開啟" : "關閉")
                + "｜自動清理: " + (autoDeleteDays == 0 ? "關閉" : autoDeleteDays + "天"));

        ioExecutor.execute(() -> {
            int deleted = PhotoHelper.cleanUpOldFiles(savedFolder, autoDeleteDays);
            if (deleted > 0) {
                writeLog("已自動清理 " + deleted + " 張超過 " + autoDeleteDays + " 天的舊照片。");
            }
        });

        buildUI();
        refreshHeaderSubtitle();
        refreshChemChips();
        refreshGrid();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TARGET_INDEX, targetIndex);
        outState.putString(KEY_PENDING_PHOTO, pendingPhotoPath);
    }

    private void restorePhotoState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            targetIndex = savedInstanceState.getInt(KEY_TARGET_INDEX, -1);
            pendingPhotoPath = savedInstanceState.getString(KEY_PENDING_PHOTO, null);
        } else {
            targetIndex = prefs.getInt(KEY_TARGET_INDEX, -1);
            pendingPhotoPath = prefs.getString(KEY_PENDING_PHOTO, null);
        }
        for (int i = 0; i < 6; i++) {
            String path = prefs.getString("slot_path_" + i, null);
            if (!TextUtils.isEmpty(path)) {
                File f = new File(path);
                if (f.exists() && f.length() > 0) {
                    photoFiles[i] = f;
                }
            }
        }
    }

    private void persistPhotoSlots() {
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < 6; i++) {
            ed.putString("slot_path_" + i, (photoFiles[i] != null && photoFiles[i].exists())
                    ? photoFiles[i].getAbsolutePath() : null);
        }
        ed.putString("SelectedChemical", selectedChemical);
        ed.putInt(KEY_TARGET_INDEX, targetIndex);
        ed.putString(KEY_PENDING_PHOTO, pendingPhotoPath);
        ed.apply();
    }

    private void registerLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        if (pendingSlotAfterPermission >= 0) {
                            int slot = pendingSlotAfterPermission;
                            pendingSlotAfterPermission = -1;
                            takePictureForSlot(slot);
                        }
                    } else {
                        Toast.makeText(this, "⚠️ 需要相機權限才能進行現場拍照", Toast.LENGTH_LONG).show();
                    }
                }
        );

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && targetIndex >= 0 && targetIndex < 6) {
                        File captured = (pendingPhotoPath != null) ? new File(pendingPhotoPath) : null;
                        if (captured == null || !captured.exists() || captured.length() == 0) {
                            captured = findLatestFallbackPhoto(targetIndex);
                        }
                        if (captured != null && captured.exists() && captured.length() > 0) {
                            final int slotIdx = targetIndex;
                            final File finalFile = captured;
                            photoFiles[slotIdx] = finalFile;
                            pendingPhotoPath = null;
                            persistPhotoSlots();

                            ioExecutor.execute(() -> {
                                try {
                                    PhotoHelper.processCapturedPhoto(finalFile, imgQuality);
                                } catch (Exception e) {
                                    writeLog("影像處理失敗: " + e.getMessage());
                                }
                                if (autoBackup) {
                                    backupSinglePhoto(slotIdx);
                                }
                                mainHandler.post(() -> {
                                    refreshGrid();
                                    String label = getDesc(slotIdx).replace("\n", "");
                                    Toast.makeText(
                                            MainActivity.this,
                                            autoBackup
                                                    ? "📸 【" + label + "】拍攝並已自動備份！"
                                                    : "📸 【" + label + "】拍攝完成！",
                                            Toast.LENGTH_SHORT
                                    ).show();
                                });
                            });
                            writeLog("相機拍照成功: " + finalFile.getName());
                        }
                    }
                    pendingPhotoPath = null;
                    persistPhotoSlots();
                }
        );
    }

    private File findLatestFallbackPhoto(int slotIdx) {
        File[] files = folder.listFiles();
        if (files == null) return null;
        File best = null;
        long lastMod = Long.MIN_VALUE;
        String prefix = (slotIdx + 1) + "_";
        for (File f : files) {
            if (f.getName().startsWith(prefix) && f.length() > 0 && f.lastModified() > lastMod) {
                best = f;
                lastMod = f.lastModified();
            }
        }
        return best;
    }

    // ===================== UI Helpers =====================

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBg(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            gd.setStroke(dp(strokeDp), strokeColor);
        }
        return gd;
    }

    private GradientDrawable verticalGradient(int topColor, int bottomColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor}
        );
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    // ===================== Build Main UI =====================

    private void buildUI() {
        LinearLayout rootContainer = new LinearLayout(this);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setBackgroundColor(COL_BG_APP);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        LinearLayout layMain = new LinearLayout(this);
        layMain.setOrientation(LinearLayout.VERTICAL);
        layMain.setPadding(dp(16), dp(18), dp(16), dp(16));
        scrollView.addView(layMain);

        // --- 1. Top Header Bar ---
        LinearLayout layHeader = new LinearLayout(this);
        layHeader.setOrientation(LinearLayout.HORIZONTAL);
        layHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout layTitleCol = new LinearLayout(this);
        layTitleCol.setOrientation(LinearLayout.VERTICAL);
        layTitleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView txtTitle = new TextView(this);
        txtTitle.setText("廠內藥液槽車回報");
        txtTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        txtTitle.setTextColor(COL_TEXT_PRIMARY);
        txtTitle.setTypeface(null, Typeface.BOLD);
        layTitleCol.addView(txtTitle);

        txtSubtitle = new TextView(this);
        txtSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtSubtitle.setTextColor(COL_TEXT_MUTED);
        txtSubtitle.setSingleLine(true);
        txtSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        layTitleCol.addView(txtSubtitle);
        layHeader.addView(layTitleCol);

        layHeader.addView(createTopPillButton("📁 圖庫", v -> showGalleryDialog(null, null)));
        layHeader.addView(createSpacer(6, 1));
        layHeader.addView(createTopPillButton("📜", v -> showLogDialog()));
        layHeader.addView(createSpacer(6, 1));
        layHeader.addView(createTopPillButton("⚙️", v -> showSettings()));
        layMain.addView(layHeader);

        // --- 2. Chemical Selector Card (1-Tap Chip Bar) ---
        cardChem = new LinearLayout(this);
        cardChem.setOrientation(LinearLayout.VERTICAL);
        cardChem.setBackground(roundedBg(COL_BG_CARD, 14, COL_BORDER, 1));
        cardChem.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lpChemCard = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpChemCard.topMargin = dp(16);
        cardChem.setLayoutParams(lpChemCard);

        lblChemTitle = new TextView(this);
        lblChemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lblChemTitle.setTypeface(null, Typeface.BOLD);
        cardChem.addView(lblChemTitle);

        HorizontalScrollView hsvChem = new HorizontalScrollView(this);
        hsvChem.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams lpHsv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpHsv.topMargin = dp(10);
        hsvChem.setLayoutParams(lpHsv);

        layChemChips = new LinearLayout(this);
        layChemChips.setOrientation(LinearLayout.HORIZONTAL);
        hsvChem.addView(layChemChips);
        cardChem.addView(hsvChem);
        layMain.addView(cardChem);

        // --- 3. Progress Indicator Card ---
        LinearLayout cardProgress = new LinearLayout(this);
        cardProgress.setOrientation(LinearLayout.VERTICAL);
        cardProgress.setBackground(roundedBg(COL_BG_CARD, 14, COL_BORDER, 1));
        cardProgress.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lpProg = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpProg.topMargin = dp(12);
        cardProgress.setLayoutParams(lpProg);

        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);

        txtProgressLabel = new TextView(this);
        txtProgressLabel.setText("📸 現場安全檢核拍照");
        txtProgressLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        txtProgressLabel.setTextColor(COL_TEXT_PRIMARY);
        txtProgressLabel.setTypeface(null, Typeface.BOLD);
        txtProgressLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        progRow.addView(txtProgressLabel);

        txtProgressBadge = new TextView(this);
        txtProgressBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtProgressBadge.setTypeface(null, Typeface.BOLD);
        txtProgressBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        progRow.addView(txtProgressBadge);
        cardProgress.addView(progRow);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(6);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        lpBar.topMargin = dp(10);
        progressBar.setLayoutParams(lpBar);
        cardProgress.addView(progressBar);
        layMain.addView(cardProgress);

        // --- 4. 2x3 Photo Checklist Grid ---
        LinearLayout layGrid = new LinearLayout(this);
        layGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpGrid = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpGrid.topMargin = dp(10);
        layGrid.setLayoutParams(lpGrid);

        int slotIdx = 0;
        for (int r = 0; r < 2; r++) {
            LinearLayout layRow = new LinearLayout(this);
            layRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                SlotCardHolder holder = createSlotCard(slotIdx);
                slotHolders[slotIdx] = holder;
                layRow.addView(holder.root);
                slotIdx++;
            }
            layGrid.addView(layRow);
        }
        layMain.addView(layGrid);

        rootContainer.addView(scrollView);

        // --- 5. Fixed Bottom Action Dock ---
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackgroundColor(COL_BG_CARD);
        dock.setPadding(dp(14), dp(12), dp(14), dp(14));
        dock.setElevation(dp(8));

        Button btnSave = createDockButton("💾 備份", COL_BLUE, 1.0f, v -> saveToLocalFolder());
        btnSend = createDockButton("📧 寄出 (0/6)", COL_EMERALD_DARK, 1.35f, v -> sendPhotos());
        Button btnClear = createDockButton("🗑️ 清空", COL_RED, 0.85f, v -> confirmClearAll());

        dock.addView(btnSave);
        dock.addView(createSpacer(8, 1));
        dock.addView(btnSend);
        dock.addView(createSpacer(8, 1));
        dock.addView(btnClear);

        rootContainer.addView(dock);
        setContentView(rootContainer);
    }

    private TextView createTopPillButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTextColor(COL_TEXT_PRIMARY);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedBg(COL_BG_CARD, 10, COL_BORDER, 1));
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        btn.setOnClickListener(listener);
        return btn;
    }

    private Button createDockButton(String text, int bgColor, float weight, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setBackground(roundedBg(bgColor, 12, 0, 0));
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(50), weight));
        btn.setOnClickListener(listener);
        return btn;
    }

    private View createSpacer(int wDp, int hDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    private SlotCardHolder createSlotCard(final int idx) {
        SlotCardHolder h = new SlotCardHolder();

        FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(156), 1.0f);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        card.setLayoutParams(lp);
        card.setClipToOutline(true);
        card.setOnClickListener(v -> onGridCardClick(idx));
        h.root = card;

        // Thumbnail layer (visible when photo exists)
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setVisibility(View.GONE);
        card.addView(iv);
        h.imgThumb = iv;

        // Dark bottom scrim over thumbnail for legible text
        View scrim = new View(this);
        scrim.setBackground(verticalGradient(Color.parseColor("#22000000"), Color.parseColor("#DD0B1120"), 14));
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setVisibility(View.GONE);
        card.addView(scrim);
        h.scrim = scrim;

        // Empty state centered content
        LinearLayout emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(6), dp(22), dp(6), dp(10));
        emptyBox.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView txtIcon = new TextView(this);
        txtIcon.setText(slotIcons[idx]);
        txtIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        txtIcon.setGravity(Gravity.CENTER);
        emptyBox.addView(txtIcon);
        h.txtEmptyIcon = txtIcon;

        TextView txtLabel = new TextView(this);
        txtLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        txtLabel.setTextColor(COL_TEXT_PRIMARY);
        txtLabel.setTypeface(null, Typeface.BOLD);
        txtLabel.setGravity(Gravity.CENTER);
        txtLabel.setPadding(0, dp(6), 0, dp(4));
        emptyBox.addView(txtLabel);
        h.txtEmptyLabel = txtLabel;

        TextView txtHint = new TextView(this);
        txtHint.setText("點擊拍照");
        txtHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        txtHint.setTextColor(COL_CYAN);
        txtHint.setBackground(roundedBg(Color.parseColor("#102A43"), 8, 0, 0));
        txtHint.setPadding(dp(8), dp(2), dp(8), dp(2));
        emptyBox.addView(txtHint);
        h.txtEmptyHint = txtHint;

        card.addView(emptyBox);
        h.emptyBox = emptyBox;

        // Top-left slot number badge (01..06)
        TextView txtNum = new TextView(this);
        txtNum.setText(String.format(Locale.US, "%02d", idx + 1));
        txtNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        txtNum.setTypeface(null, Typeface.BOLD);
        txtNum.setTextColor(COL_TEXT_MUTED);
        txtNum.setBackground(roundedBg(Color.parseColor("#B00B1120"), 6, 0, 0));
        txtNum.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams lpNum = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lpNum.setMargins(dp(8), dp(8), 0, 0);
        txtNum.setLayoutParams(lpNum);
        card.addView(txtNum);
        h.txtSlotNum = txtNum;

        // Top-right green check badge (visible when captured)
        TextView txtCheck = new TextView(this);
        txtCheck.setText("✓");
        txtCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtCheck.setTypeface(null, Typeface.BOLD);
        txtCheck.setTextColor(Color.WHITE);
        txtCheck.setGravity(Gravity.CENTER);
        txtCheck.setBackground(roundedBg(COL_EMERALD, 12, 0, 0));
        FrameLayout.LayoutParams lpChk = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
        lpChk.setMargins(0, dp(8), dp(8), 0);
        txtCheck.setLayoutParams(lpChk);
        txtCheck.setVisibility(View.GONE);
        card.addView(txtCheck);
        h.txtCheckBadge = txtCheck;

        // Bottom overlay label (visible when captured)
        TextView txtOverlay = new TextView(this);
        txtOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtOverlay.setTextColor(Color.WHITE);
        txtOverlay.setTypeface(null, Typeface.BOLD);
        txtOverlay.setGravity(Gravity.CENTER);
        txtOverlay.setPadding(dp(4), dp(4), dp(4), dp(8));
        FrameLayout.LayoutParams lpOv = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        txtOverlay.setLayoutParams(lpOv);
        txtOverlay.setVisibility(View.GONE);
        card.addView(txtOverlay);
        h.txtOverlayLabel = txtOverlay;

        return h;
    }

    // ===================== Refresh UI States =====================

    private void refreshHeaderSubtitle() {
        String today = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date());
        String target = TextUtils.isEmpty(emailAddress) ? "尚未設定收件人" : emailAddress;
        txtSubtitle.setText(today + " · 收件：" + target);
    }

    private boolean hasSelectedChemical() {
        return !TextUtils.isEmpty(selectedChemical);
    }

    private void refreshChemChips() {
        layChemChips.removeAllViews();
        chemChipViews.clear();

        boolean hasChem = hasSelectedChemical();
        if (hasChem) {
            cardChem.setBackground(roundedBg(COL_BG_CARD, 14, COL_CYAN, 1));
            lblChemTitle.setText("✅ 已確認本次藥液：【" + selectedChemical + "】");
            lblChemTitle.setTextColor(COL_CYAN);
        } else {
            cardChem.setBackground(roundedBg(COL_BG_CARD, 14, COL_AMBER, 2));
            lblChemTitle.setText("⚠️ 請先選擇本次槽車藥液（尚未確認）");
            lblChemTitle.setTextColor(COL_AMBER);
        }

        boolean isCustomActive = hasChem;
        for (String opt : chemOptions) {
            if (opt.equals(selectedChemical)) {
                isCustomActive = false;
                break;
            }
        }

        for (String opt : chemOptions) {
            final boolean isCustomBtn = opt.startsWith("+");
            boolean isSelected = hasChem && (isCustomBtn ? isCustomActive : opt.equals(selectedChemical));
            String label = (isCustomBtn && isCustomActive) ? ("✏️ " + selectedChemical) : opt;

            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setTextColor(isSelected ? COL_CYAN : COL_TEXT_MUTED);
            chip.setBackground(roundedBg(
                    isSelected ? COL_CYAN_TINT : COL_BG_ELEVATED,
                    18,
                    isSelected ? COL_CYAN : COL_BORDER,
                    1
            ));
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                if (isCustomBtn) {
                    showCustomChemDialog(null);
                } else {
                    selectedChemical = opt;
                    persistPhotoSlots();
                    refreshChemChips();
                    refreshGrid();
                }
            });

            layChemChips.addView(chip);
            chemChipViews.add(chip);
        }
    }

    private String getDesc(int idx) {
        String chemLabel = hasSelectedChemical() ? selectedChemical : "藥液";
        return baseDescriptions[idx].replace("@@", chemLabel);
    }

    private void refreshGrid() {
        int completedCount = 0;
        for (int i = 0; i < 6; i++) {
            SlotCardHolder h = slotHolders[i];
            String label = getDesc(i);
            File f = photoFiles[i];
            boolean hasPhoto = (f != null && f.exists() && f.length() > 0);

            if (hasPhoto) {
                completedCount++;
                h.root.setBackground(roundedBg(COL_BG_ELEVATED, 14, COL_EMERALD, 2));
                h.emptyBox.setVisibility(View.GONE);
                h.imgThumb.setVisibility(View.VISIBLE);
                h.scrim.setVisibility(View.VISIBLE);
                h.txtCheckBadge.setVisibility(View.VISIBLE);
                h.txtOverlayLabel.setVisibility(View.VISIBLE);
                h.txtOverlayLabel.setText(label);

                final File fileRef = f;
                ioExecutor.execute(() -> {
                    Bitmap thumb = PhotoHelper.decodeUprightBitmap(fileRef, 260, 260);
                    mainHandler.post(() -> {
                        if (photoFiles[slotIndexOf(h)] == fileRef && thumb != null) {
                            h.imgThumb.setImageBitmap(thumb);
                        }
                    });
                });
            } else {
                h.root.setBackground(roundedBg(COL_BG_CARD, 14, COL_BORDER, 1));
                h.imgThumb.setImageDrawable(null);
                h.imgThumb.setVisibility(View.GONE);
                h.scrim.setVisibility(View.GONE);
                h.txtCheckBadge.setVisibility(View.GONE);
                h.txtOverlayLabel.setVisibility(View.GONE);
                h.emptyBox.setVisibility(View.VISIBLE);
                h.txtEmptyLabel.setText(label);
            }
        }

        // Update progress bar & badge
        progressBar.setProgress(completedCount);
        boolean allDone = (completedCount == 6);
        int barColor = allDone ? COL_EMERALD : COL_CYAN;
        progressBar.getProgressDrawable().setTint(barColor);

        txtProgressBadge.setText(allDone ? "✓ 全部完成 6/6" : ("已完成 " + completedCount + " / 6"));
        txtProgressBadge.setTextColor(allDone ? COL_EMERALD : COL_CYAN);
        txtProgressBadge.setBackground(roundedBg(
                allDone ? Color.parseColor("#064E3B") : COL_CYAN_TINT, 10, 0, 0));

        btnSend.setText("📧 寄出 (" + completedCount + "/6)");
        btnSend.setBackground(roundedBg(allDone ? COL_EMERALD : COL_EMERALD_DARK, 12, 0, 0));
    }

    private int slotIndexOf(SlotCardHolder holder) {
        for (int i = 0; i < 6; i++) {
            if (slotHolders[i] == holder) return i;
        }
        return -1;
    }

    // ===================== Camera & Photo Actions =====================

    private void onGridCardClick(int idx) {
        if (photoFiles[idx] != null && photoFiles[idx].exists()) {
            showPreviewDialog(idx, false, null, null, null);
        } else if (!hasSelectedChemical()) {
            showRequiredChemDialog(() -> ensureCameraAndShoot(idx));
        } else {
            ensureCameraAndShoot(idx);
        }
    }

    private void showRequiredChemDialog(final Runnable onConfirmed) {
        new AlertDialog.Builder(this)
                .setTitle("🧪 請先確認本次槽車藥液")
                .setItems(chemOptions, (dialog, which) -> {
                    String picked = chemOptions[which];
                    if (picked.startsWith("+")) {
                        showCustomChemDialog(onConfirmed);
                    } else {
                        selectedChemical = picked;
                        persistPhotoSlots();
                        refreshChemChips();
                        refreshGrid();
                        if (onConfirmed != null) onConfirmed.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void ensureCameraAndShoot(int idx) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            takePictureForSlot(idx);
        } else {
            pendingSlotAfterPermission = idx;
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void takePictureForSlot(int idx) {
        targetIndex = idx;
        String safeDesc = getDesc(idx).replace("\n", "").replace("/", "_");
        File photoFile = new File(folder, (idx + 1) + "_" + safeDesc + "_" + System.currentTimeMillis() + ".jpg");
        pendingPhotoPath = photoFile.getAbsolutePath();
        persistPhotoSlots();

        Uri photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            cameraLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "❌ 找不到系統相機應用程式", Toast.LENGTH_LONG).show();
            writeLog("無法啟動相機: " + e.getMessage());
        }
    }

    private void confirmClearAll() {
        int count = countCapturedPhotos();
        if (count == 0 && !hasSelectedChemical()) {
            Toast.makeText(this, "目前沒有照片可清空", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("🗑️ 確認清空本車次紀錄？")
                .setMessage("將清除目前已拍攝的 " + count + " 張現場照片，並重置藥液選擇（已備份至圖庫的歷史照片不會受影響）。")
                .setPositiveButton("確定清空", (d, w) -> {
                    for (int i = 0; i < 6; i++) {
                        if (photoFiles[i] != null) {
                            photoFiles[i].delete();
                            photoFiles[i] = null;
                        }
                    }
                    selectedChemical = "";
                    persistPhotoSlots();
                    refreshChemChips();
                    refreshGrid();
                    Toast.makeText(this, "✅ 已清空並重置藥液選擇", Toast.LENGTH_SHORT).show();
                    writeLog("手動全部清空重來並重置藥液");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int countCapturedPhotos() {
        int count = 0;
        for (File f : photoFiles) {
            if (f != null && f.exists() && f.length() > 0) count++;
        }
        return count;
    }

    private void backupSinglePhoto(int idx) {
        try {
            File source = photoFiles[idx];
            if (source != null && source.exists()) {
                String timePrefix = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                String pureDesc = getDesc(idx).replace("\n", "");
                File dest = new File(savedFolder, timePrefix + "_" + selectedChemical + "_" + pureDesc + ".jpg");
                PhotoHelper.copyFile(source, dest);
                writeLog("背景自動備份: " + dest.getName());
            }
        } catch (Exception e) {
            writeLog("自動備份失敗: " + e.getMessage());
        }
    }

    private void saveToLocalFolder() {
        int count = countCapturedPhotos();
        if (count == 0) {
            Toast.makeText(this, "⚠️ 請先至少拍一張照片！", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasSelectedChemical()) {
            showRequiredChemDialog(this::saveToLocalFolder);
            return;
        }

        AlertDialog loading = createLoadingDialog("正在備份 " + count + " 張照片至專屬圖庫…");
        loading.show();

        ioExecutor.execute(() -> {
            try {
                int savedCount = 0;
                String timePrefix = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                for (int i = 0; i < 6; i++) {
                    File source = photoFiles[i];
                    if (source != null && source.exists()) {
                        String pureDesc = getDesc(i).replace("\n", "");
                        File dest = new File(savedFolder, timePrefix + "_" + selectedChemical + "_" + pureDesc + ".jpg");
                        PhotoHelper.copyFile(source, dest);
                        savedCount++;
                    }
                }
                final int finalSaved = savedCount;
                mainHandler.post(() -> {
                    loading.dismiss();
                    Toast.makeText(this, "✅ 成功備份 " + finalSaved + " 張【" + selectedChemical + "】照片！", Toast.LENGTH_LONG).show();
                    writeLog("手動備份 " + finalSaved + " 張【" + selectedChemical + "】照片至 SavedReports");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    loading.dismiss();
                    Toast.makeText(this, "❌ 備份失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void sendPhotos() {
        int count = countCapturedPhotos();
        if (count == 0) {
            Toast.makeText(this, "⚠️ 請先至少拍一張照片！", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasSelectedChemical()) {
            showRequiredChemDialog(this::sendPhotos);
            return;
        }
        if (TextUtils.isEmpty(emailAddress)) {
            Toast.makeText(this, "⚠️ 請先點右上角 ⚙️ 設定收件信箱！", Toast.LENGTH_LONG).show();
            return;
        }

        // Final chemical double-check confirmation before sending email
        new AlertDialog.Builder(this)
                .setTitle("🧪 寄出前請再次確認藥液")
                .setMessage("• 本次藥液：【" + selectedChemical + "】\n"
                        + "• 拍攝照片：" + count + " / 6 張\n"
                        + "• 收件信箱：" + emailAddress + "\n\n"
                        + "請確認藥液名稱與現場槽車一致！")
                .setPositiveButton("✅ 確認無誤，寄出", (d, w) -> performSendPhotos(count))
                .setNeutralButton("🔄 更改藥液", (d, w) -> showRequiredChemDialog(this::sendPhotos))
                .setNegativeButton("取消", null)
                .show();
    }

    private void performSendPhotos(int count) {
        ArrayList<Uri> imageUris = new ArrayList<>();
        for (File f : photoFiles) {
            if (f != null && f.exists()) {
                imageUris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            }
        }

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        String subject = timeStr + " " + selectedChemical + "槽車打藥";
        String body = "您好，\n\n現場已完成「" + selectedChemical + "」槽車作業檢核，共拍攝 "
                + count + " 張照片，請見附件。\n";

        launchEmailIntent(imageUris, subject, body, "寄送藥液槽車回報…");
    }

    private void sendHistoricalPhotos(HashSet<String> selectedPaths) {
        if (TextUtils.isEmpty(emailAddress)) {
            Toast.makeText(this, "⚠️ 請先於設定填寫收件信箱！", Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<Uri> imageUris = new ArrayList<>();
        for (String path : selectedPaths) {
            File f = new File(path);
            if (f.exists()) {
                imageUris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            }
        }
        if (imageUris.isEmpty()) {
            Toast.makeText(this, "⚠️ 選取的檔案已不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        String subject = timeStr + " 槽車打藥 (歷史備份補寄)";
        String body = "您好，\n\n此為歷史備份圖庫中補寄之照片，共挑選 " + imageUris.size() + " 張，請見附件。\n";

        launchEmailIntent(imageUris, subject, body, "寄送歷史備份照片…");
    }

    private void launchEmailIntent(ArrayList<Uri> imageUris, String subject, String body, String chooserTitle) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        emailIntent.setType("image/jpeg");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{emailAddress});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        emailIntent.putExtra(Intent.EXTRA_TEXT, body);
        emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(emailIntent, chooserTitle));
            writeLog("開啟郵件寄送: " + subject + " (" + imageUris.size() + " 張)");
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "❌ 找不到可寄送郵件的 App", Toast.LENGTH_LONG).show();
        }
    }

    private AlertDialog createLoadingDialog(String message) {
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.HORIZONTAL);
        lay.setGravity(Gravity.CENTER_VERTICAL);
        lay.setPadding(dp(20), dp(20), dp(20), dp(20));

        ProgressBar pb = new ProgressBar(this);
        lay.addView(pb);

        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(COL_TEXT_PRIMARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(16), 0, 0, 0);
        lay.addView(tv);

        return new AlertDialog.Builder(this)
                .setView(lay)
                .setCancelable(false)
                .create();
    }

    // ===================== Dialogs =====================

    private void showGalleryDialog(final String expandedDate, final HashSet<String> previousSelections) {
        final HashSet<String> selections = (previousSelections == null) ? new HashSet<>() : previousSelections;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📁 歷史備份圖庫");

        File[] files = savedFolder.listFiles();
        if (files == null || files.length == 0) {
            builder.setMessage("目前沒有任何備份的照片。");
            builder.setPositiveButton("關閉", null);
            builder.show();
            return;
        }

        Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        LinkedHashMap<String, ArrayList<File>> groupedMap = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        for (File f : files) {
            if (!f.isFile()) continue;
            String dateStr = sdf.format(new Date(f.lastModified()));
            groupedMap.computeIfAbsent(dateStr, k -> new ArrayList<>()).add(f);
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout layList = new LinearLayout(this);
        layList.setOrientation(LinearLayout.VERTICAL);
        layList.setPadding(dp(14), dp(12), dp(14), dp(12));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        String autoExpand = (expandedDate != null) ? expandedDate
                : (groupedMap.isEmpty() ? null : groupedMap.keySet().iterator().next());

        for (Map.Entry<String, ArrayList<File>> entry : groupedMap.entrySet()) {
            final String dateStr = entry.getKey();
            final ArrayList<File> dateFiles = entry.getValue();

            LinearLayout groupLayout = new LinearLayout(this);
            groupLayout.setOrientation(LinearLayout.VERTICAL);
            groupLayout.setPadding(0, 0, 0, dp(10));

            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setBackground(roundedBg(COL_BG_ELEVATED, 10, COL_BORDER, 1));
            headerRow.setPadding(dp(12), dp(10), dp(10), dp(10));

            boolean isExpanded = dateStr.equals(autoExpand);
            final TextView tvDateHeader = new TextView(this);
            tvDateHeader.setText("📅 " + dateStr + "（" + dateFiles.size() + " 張） " + (isExpanded ? "▲" : "▼"));
            tvDateHeader.setTextColor(COL_TEXT_PRIMARY);
            tvDateHeader.setTypeface(null, Typeface.BOLD);
            tvDateHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvDateHeader.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            headerRow.addView(tvDateHeader);

            TextView btnSelectDay = new TextView(this);
            btnSelectDay.setText("全選當日");
            btnSelectDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnSelectDay.setTextColor(COL_CYAN);
            btnSelectDay.setBackground(roundedBg(COL_CYAN_TINT, 8, 0, 0));
            btnSelectDay.setPadding(dp(8), dp(4), dp(8), dp(4));
            headerRow.addView(btnSelectDay);
            groupLayout.addView(headerRow);

            final LinearLayout layContent = new LinearLayout(this);
            layContent.setOrientation(LinearLayout.VERTICAL);
            layContent.setPadding(dp(4), dp(10), dp(4), dp(4));
            layContent.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            tvDateHeader.setOnClickListener(v -> {
                boolean nowVisible = layContent.getVisibility() != View.VISIBLE;
                layContent.setVisibility(nowVisible ? View.VISIBLE : View.GONE);
                tvDateHeader.setText("📅 " + dateStr + "（" + dateFiles.size() + " 張） " + (nowVisible ? "▲" : "▼"));
            });

            final List<CheckBox> dayCheckBoxes = new ArrayList<>();

            for (File f : dateFiles) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(roundedBg(COL_BG_APP, 10, COL_BORDER, 1));
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lpRow.bottomMargin = dp(8);
                row.setLayoutParams(lpRow);

                CheckBox chk = new CheckBox(this);
                chk.setChecked(selections.contains(f.getAbsolutePath()));
                chk.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) selections.add(f.getAbsolutePath());
                    else selections.remove(f.getAbsolutePath());
                    updateGallerySendButton(dialogRef[0], selections.size());
                });
                dayCheckBoxes.add(chk);
                row.addView(chk);

                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundColor(COL_BG_ELEVATED);
                LinearLayout.LayoutParams lpImg = new LinearLayout.LayoutParams(dp(52), dp(52));
                lpImg.setMargins(dp(4), 0, dp(10), 0);
                thumb.setLayoutParams(lpImg);
                thumb.setOnClickListener(v -> {
                    dialogRef[0].dismiss();
                    showPreviewDialog(0, true, f, dateStr, selections);
                });
                row.addView(thumb);

                ioExecutor.execute(() -> {
                    Bitmap bmp = PhotoHelper.decodeUprightBitmap(f, 120, 120);
                    mainHandler.post(() -> {
                        if (bmp != null) thumb.setImageBitmap(bmp);
                    });
                });

                TextView tvName = new TextView(this);
                tvName.setText(formatSavedFileName(f.getName()));
                tvName.setTextColor(COL_TEXT_PRIMARY);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
                tvName.setOnClickListener(v -> chk.toggle());
                row.addView(tvName);

                TextView btnDel = new TextView(this);
                btnDel.setText("🗑️");
                btnDel.setGravity(Gravity.CENTER);
                btnDel.setBackground(roundedBg(COL_RED, 8, 0, 0));
                btnDel.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
                btnDel.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("刪除確認")
                        .setMessage("確定要永久刪除這張備份嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            f.delete();
                            selections.remove(f.getAbsolutePath());
                            dialogRef[0].dismiss();
                            showGalleryDialog(dateStr, selections);
                        })
                        .setNegativeButton("取消", null)
                        .show());
                row.addView(btnDel);

                layContent.addView(row);
            }

            btnSelectDay.setOnClickListener(v -> {
                boolean allSelected = true;
                for (File f : dateFiles) {
                    if (!selections.contains(f.getAbsolutePath())) {
                        allSelected = false;
                        break;
                    }
                }
                for (int i = 0; i < dateFiles.size(); i++) {
                    boolean next = !allSelected;
                    dayCheckBoxes.get(i).setChecked(next);
                }
                layContent.setVisibility(View.VISIBLE);
                tvDateHeader.setText("📅 " + dateStr + "（" + dateFiles.size() + " 張） ▲");
            });

            groupLayout.addView(layContent);
            layList.addView(groupLayout);
        }

        sv.addView(layList);
        builder.setView(sv);
        builder.setPositiveButton("關閉", null);
        builder.setNegativeButton("📧 寄出選取 (" + selections.size() + ")", null);

        dialogRef[0] = builder.create();
        dialogRef[0].setOnShowListener(di -> {
            Button btnSendSelected = dialogRef[0].getButton(AlertDialog.BUTTON_NEGATIVE);
            btnSendSelected.setTextColor(COL_EMERALD);
            btnSendSelected.setOnClickListener(v -> {
                if (selections.isEmpty()) {
                    Toast.makeText(MainActivity.this, "⚠️ 請先勾選要寄出的照片！", Toast.LENGTH_SHORT).show();
                } else {
                    sendHistoricalPhotos(selections);
                }
            });
        });
        dialogRef[0].show();
    }

    private void updateGallerySendButton(AlertDialog dialog, int count) {
        if (dialog == null) return;
        Button btn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (btn != null) {
            btn.setText("📧 寄出選取 (" + count + ")");
        }
    }

    private String formatSavedFileName(String fileName) {
        String raw = fileName.replace(".jpg", "");
        String[] parts = raw.split("_");
        if (parts.length >= 4 && parts[1].length() >= 4) {
            String timeStr = parts[1].substring(0, 2) + ":" + parts[1].substring(2, 4);
            return "🕒 " + timeStr + "\n" + parts[2] + " · " + parts[3];
        }
        return raw;
    }

    private void showPreviewDialog(
            final int idx,
            boolean isFromGallery,
            File specificFile,
            final String expandedDate,
            final HashSet<String> selections
    ) {
        File fileToPreview = isFromGallery ? specificFile : photoFiles[idx];
        if (fileToPreview == null || !fileToPreview.exists()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isFromGallery
                ? "歷史預覽：" + formatSavedFileName(fileToPreview.getName()).replace("\n", " ")
                : "照片預覽：" + getDesc(idx).replace("\n", ""));

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setGravity(Gravity.CENTER);
        lay.setPadding(dp(16), dp(12), dp(16), dp(8));

        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        iv.setLayoutParams(lp);
        lay.addView(iv);

        ioExecutor.execute(() -> {
            Bitmap bmp = PhotoHelper.decodeUprightBitmap(fileToPreview, 1080, 1080);
            mainHandler.post(() -> {
                if (bmp != null) iv.setImageBitmap(bmp);
            });
        });

        builder.setView(lay);
        if (isFromGallery) {
            builder.setPositiveButton("返回圖庫", (d, w) -> showGalleryDialog(expandedDate, selections));
        } else {
            builder.setPositiveButton("📸 重新拍攝", (d, w) -> ensureCameraAndShoot(idx));
            builder.setNeutralButton("🗑️ 刪除", (d, w) -> {
                photoFiles[idx].delete();
                photoFiles[idx] = null;
                persistPhotoSlots();
                refreshGrid();
            });
            builder.setNegativeButton("保留", null);
        }
        builder.show();
    }

    private void showCustomChemDialog(final Runnable onConfirmed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🧪 輸入自訂藥液名稱");

        LinearLayout lay = new LinearLayout(this);
        lay.setPadding(dp(20), dp(12), dp(20), dp(8));

        final EditText input = new EditText(this);
        input.setHint("例如：鹽酸、雙氧水…");
        input.setTextColor(COL_TEXT_PRIMARY);
        input.setHintTextColor(COL_TEXT_MUTED);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lay.addView(input);
        builder.setView(lay);

        builder.setPositiveButton("確定", (dialog, which) -> {
            String val = input.getText().toString().trim();
            if (!val.isEmpty()) {
                selectedChemical = val;
                persistPhotoSlots();
                refreshChemChips();
                refreshGrid();
                if (onConfirmed != null) onConfirmed.run();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ 系統設定");

        ScrollView sv = new ScrollView(this);
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(dp(20), dp(16), dp(20), dp(12));

        TextView lblEmail = createDialogLabel("收件者 Email：");
        lay.addView(lblEmail);

        final EditText edtEmail = new EditText(this);
        edtEmail.setText(emailAddress);
        edtEmail.setTextColor(COL_TEXT_PRIMARY);
        edtEmail.setHintTextColor(COL_TEXT_MUTED);
        lay.addView(edtEmail);

        CheckBox chkDefaultEmail = new CheckBox(this);
        chkDefaultEmail.setText("使用預設信箱 (范光明)");
        chkDefaultEmail.setTextColor(COL_TEXT_PRIMARY);
        chkDefaultEmail.setPadding(0, dp(8), 0, dp(12));
        chkDefaultEmail.setChecked(DEFAULT_EMAIL.equals(emailAddress));
        chkDefaultEmail.setOnCheckedChangeListener((btnView, isChecked) -> {
            if (isChecked) {
                edtEmail.setText(DEFAULT_EMAIL);
            } else if (DEFAULT_EMAIL.equals(edtEmail.getText().toString().trim())) {
                edtEmail.setText("");
            }
        });
        lay.addView(chkDefaultEmail);

        CheckBox chkAutoBackup = new CheckBox(this);
        chkAutoBackup.setText("拍照後自動備份至圖庫");
        chkAutoBackup.setTextColor(COL_TEXT_PRIMARY);
        chkAutoBackup.setPadding(0, 0, 0, dp(16));
        chkAutoBackup.setChecked(autoBackup);
        lay.addView(chkAutoBackup);

        lay.addView(createDialogLabel("照片壓縮畫質："));
        final Spinner spinQ = new Spinner(this);
        String[] qs = {"高畫質", "中畫質", "低畫質"};
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qs);
        spinQ.setAdapter(adp);
        int qIdx = Arrays.asList(qs).indexOf(imgQuality);
        spinQ.setSelection(Math.max(0, qIdx));
        lay.addView(spinQ);

        TextView lblDays = createDialogLabel("圖庫備份自動清理：");
        lblDays.setPadding(0, dp(16), 0, 0);
        lay.addView(lblDays);

        final Spinner spinDays = new Spinner(this);
        String[] daysStr = {"不自動刪除", "3天後自動刪除", "7天後自動刪除", "14天後自動刪除", "30天後自動刪除"};
        final int[] daysValues = {0, 3, 7, 14, 30};
        ArrayAdapter<String> adpDays = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, daysStr);
        spinDays.setAdapter(adpDays);
        int selectedDayPos = 2;
        for (int i = 0; i < daysValues.length; i++) {
            if (daysValues[i] == autoDeleteDays) {
                selectedDayPos = i;
                break;
            }
        }
        spinDays.setSelection(selectedDayPos);
        lay.addView(spinDays);

        sv.addView(lay);
        builder.setView(sv);
        builder.setPositiveButton("儲存設定", (dialog, which) -> {
            emailAddress = edtEmail.getText().toString().trim();
            imgQuality = qs[spinQ.getSelectedItemPosition()];
            autoDeleteDays = daysValues[spinDays.getSelectedItemPosition()];
            autoBackup = chkAutoBackup.isChecked();

            prefs.edit()
                    .putString("TargetEmail", emailAddress)
                    .putString("ImgQuality", imgQuality)
                    .putInt("AutoDeleteDays", autoDeleteDays)
                    .putBoolean("AutoBackup", autoBackup)
                    .apply();

            refreshHeaderSubtitle();
            Toast.makeText(this, "✅ 設定已儲存！", Toast.LENGTH_SHORT).show();
            ioExecutor.execute(() -> PhotoHelper.cleanUpOldFiles(savedFolder, autoDeleteDays));
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private TextView createDialogLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COL_CYAN);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return tv;
    }

    private void showLogDialog() {
        String content = "目前沒有任何日誌記錄。";
        if (logFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                if (sb.length() > 0) content = sb.toString();
            } catch (Exception ignored) {
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📜 內部執行日誌");
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextColor(COL_TEXT_PRIMARY);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sv.addView(tv);
        builder.setView(sv);
        builder.setPositiveButton("關閉", null);
        builder.setNegativeButton("清除日誌", (dialog, which) -> {
            logFile.delete();
            Toast.makeText(this, "🗑️ 日誌已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private synchronized void writeLog(String msg) {
        try {
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String line = "[" + timeStr + "] " + msg + "\n";
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes());
            }
        } catch (Exception ignored) {
        }
    }
}
