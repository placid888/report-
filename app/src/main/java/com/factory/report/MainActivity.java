package com.factory.report;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.core.content.FileProvider;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // 系統變數
    File folder;      
    File savedFolder; 
    File logFile;
    File[] photoFiles = new File[6];
    
    // 設定變數
    String emailAddress = "kuangming.fan@tci.com.tw";
    String selectedChemical = "氯化銅";
    String imgQuality = "高畫質";
    int autoDeleteDays = 7; 
    boolean autoBackup = false; 
    
    // UI 元件
    Button[] btnBoxes = new Button[6];
    Button btnChemSelector;
    String[] baseDescriptions = {"貨車車牌", "車輪檔", "@@\n藥液出口", "@@\n藥桶入口", "貨車\n接地線", "司機\n防護裝備"};
    String[] chemOptions = {"氯化銅", "液鹼", "氯化鐵", "氯化亞鐵", "硫酸", "酸化助劑", "自訂"};
    SharedPreferences prefs;
    Uri currentPhotoUri;
    int targetIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        emailAddress = prefs.getString("TargetEmail", "kuangming.fan@tci.com.tw");
        imgQuality = prefs.getString("ImgQuality", "高畫質");
        autoDeleteDays = prefs.getInt("AutoDeleteDays", 7);
        autoBackup = prefs.getBoolean("AutoBackup", false); 

        folder = new File(getExternalFilesDir(null), "DebugPhotos");
        if (!folder.exists()) folder.mkdirs();
        
        savedFolder = new File(getExternalFilesDir(null), "SavedReports");
        if (!savedFolder.exists()) savedFolder.mkdirs();
        
        logFile = new File(folder, "debug_log.txt");

        writeLog("程式啟動。自動備份: " + (autoBackup?"開啟":"關閉") + ", 自動刪除: " + (autoDeleteDays==0?"關閉":autoDeleteDays+"天"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        cleanUpOldPhotos();
        buildUI();
        refreshGrid();
    }

    void cleanUpOldPhotos() {
        if (autoDeleteDays <= 0) return; 
        
        long thresholdMillis = System.currentTimeMillis() - ((long) autoDeleteDays * 24 * 60 * 60 * 1000);
        File[] files = savedFolder.listFiles();
        int deletedCount = 0;
        
        if (files != null) {
            for (File f : files) {
                if (f.lastModified() < thresholdMillis) {
                    if (f.delete()) deletedCount++;
                }
            }
        }
        if (deletedCount > 0) {
            writeLog("已自動清理 " + deletedCount + " 張超過 " + autoDeleteDays + " 天的舊照片。");
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable getRoundRect(String colorHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(colorHex));
        gd.setCornerRadius(dpToPx(radiusDp));
        return gd;
    }

    private GradientDrawable getOutlineRect(String strokeColorHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.TRANSPARENT);
        gd.setStroke(dpToPx(2), Color.parseColor(strokeColorHex));
        gd.setCornerRadius(dpToPx(radiusDp));
        return gd;
    }

    void buildUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0f172a"));
        scrollView.setFillViewport(true);

        LinearLayout layMain = new LinearLayout(this);
        layMain.setOrientation(LinearLayout.VERTICAL);
        layMain.setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24));
        scrollView.addView(layMain);

        LinearLayout layTop = new LinearLayout(this);
        layTop.setOrientation(LinearLayout.HORIZONTAL);
        layTop.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView txtTitle = new TextView(this);
        txtTitle.setText("廠內藥液槽車回報");
        txtTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        txtTitle.setTextColor(Color.parseColor("#38bdf8"));
        txtTitle.getPaint().setFakeBoldText(true);
        txtTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        layTop.addView(txtTitle);

        Button btnGallery = new Button(this);
        btnGallery.setText("📁 圖庫");
        btnGallery.setTextColor(Color.WHITE);
        btnGallery.setBackground(getOutlineRect("#64748b", 8));
        btnGallery.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnGallery.setOnClickListener(v -> showGalleryDialog(null, null)); 
        layTop.addView(btnGallery);

        View spacer1 = new View(this);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(6), 1));
        layTop.addView(spacer1);

        Button btnViewLog = new Button(this);
        btnViewLog.setText("📜 日誌");
        btnViewLog.setTextColor(Color.WHITE);
        btnViewLog.setBackground(getOutlineRect("#64748b", 8));
        btnViewLog.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnViewLog.setOnClickListener(v -> showLogDialog());
        layTop.addView(btnViewLog);

        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(6), 1));
        layTop.addView(spacer2);

        Button btnSettings = new Button(this);
        btnSettings.setText("⚙️ 設定");
        btnSettings.setTextColor(Color.WHITE);
        btnSettings.setBackground(getOutlineRect("#64748b", 8));
        btnSettings.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btnSettings.setOnClickListener(v -> showSettings());
        layTop.addView(btnSettings);

        layMain.addView(layTop);

        LinearLayout layChem = new LinearLayout(this);
        layChem.setOrientation(LinearLayout.HORIZONTAL);
        layChem.setPadding(0, dpToPx(24), 0, dpToPx(24));
        layChem.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblChem = new TextView(this);
        lblChem.setText("目前藥液：");
        lblChem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        lblChem.setTextColor(Color.parseColor("#cbd5e1"));
        layChem.addView(lblChem);

        btnChemSelector = new Button(this);
        btnChemSelector.setText(selectedChemical + " ▼");
        btnChemSelector.setTextColor(Color.parseColor("#0f172a"));
        btnChemSelector.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnChemSelector.getPaint().setFakeBoldText(true);
        btnChemSelector.setBackground(getRoundRect("#f8fafc", 8));
        btnChemSelector.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnChemSelector.setOnClickListener(v -> showChemSelectDialog());
        
        LinearLayout.LayoutParams chemLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chemLp.leftMargin = dpToPx(8);
        btnChemSelector.setLayoutParams(chemLp);
        layChem.addView(btnChemSelector);
        
        layMain.addView(layChem);

        TextView lblThumbTitle = new TextView(this);
        lblThumbTitle.setText("📸 點擊下方對應格子直接拍照：");
        lblThumbTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lblThumbTitle.setTextColor(Color.parseColor("#7dd3fc"));
        lblThumbTitle.setPadding(0, dpToPx(12), 0, dpToPx(12));
        layMain.addView(lblThumbTitle);

        LinearLayout layGrid = new LinearLayout(this);
        layGrid.setOrientation(LinearLayout.VERTICAL);
        int btnId = 0;
        for (int r = 0; r < 2; r++) {
            LinearLayout layRow = new LinearLayout(this);
            layRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                Button btnBox = new Button(this);
                btnBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                btnBox.setPadding(dpToPx(2), dpToPx(8), dpToPx(2), dpToPx(8)); 
                btnBox.setLineSpacing(0, 1.2f);
                btnBox.setGravity(Gravity.CENTER); 
                
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(130), 1.0f);
                lp.setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6)); 
                btnBox.setLayoutParams(lp);
                
                final int idx = btnId;
                btnBox.setOnClickListener(v -> onGridButtonTouch(idx));
                
                btnBoxes[btnId] = btnBox;
                layRow.addView(btnBox);
                btnId++;
            }
            layGrid.addView(layRow);
        }
        layMain.addView(layGrid);

        LinearLayout layBtns = new LinearLayout(this);
        layBtns.setOrientation(LinearLayout.HORIZONTAL);
        layBtns.setPadding(0, dpToPx(32), 0, 0); 

        Button btnSave = new Button(this);
        btnSave.setText("💾 備份保存");
        btnSave.setTextColor(Color.WHITE);
        btnSave.getPaint().setFakeBoldText(true);
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnSave.setBackground(getRoundRect("#0284c7", 12));
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, dpToPx(56), 1.0f);
        btnLp1.rightMargin = dpToPx(4);
        btnSave.setLayoutParams(btnLp1);
        btnSave.setOnClickListener(v -> saveToLocalFolder()); 
        layBtns.addView(btnSave);

        Button btnSend = new Button(this);
        btnSend.setText("📧 寄出");
        btnSend.setTextColor(Color.WHITE);
        btnSend.getPaint().setFakeBoldText(true);
        btnSend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnSend.setBackground(getRoundRect("#16a34a", 12));
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, dpToPx(56), 1.0f);
        btnLp2.leftMargin = dpToPx(4);
        btnLp2.rightMargin = dpToPx(4);
        btnSend.setLayoutParams(btnLp2);
        btnSend.setOnClickListener(v -> sendPhotos());
        layBtns.addView(btnSend);

        Button btnClear = new Button(this);
        btnClear.setText("🗑️ 清空");
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnClear.setBackground(getRoundRect("#dc2626", 12));
        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, dpToPx(56), 1.0f);
        btnLp3.leftMargin = dpToPx(4);
        btnClear.setLayoutParams(btnLp3);
        btnClear.setOnClickListener(v -> clearAll());
        layBtns.addView(btnClear);

        layMain.addView(layBtns);
        setContentView(scrollView);
    }

    void writeLog(String msg) {
        try {
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String line = "[" + timeStr + "] " + msg + "\n";
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(line.getBytes());
            fos.close();
        } catch (Exception e) {}
    }

    String getDesc(int idx) {
        return baseDescriptions[idx].replace("@@", selectedChemical);
    }

    void refreshGrid() {
        for (int i = 0; i < 6; i++) {
            String rawDesc = getDesc(i);
            if (!rawDesc.contains("\n")) {
                rawDesc = rawDesc + "\n ";
            }

            if (photoFiles[i] != null && photoFiles[i].exists()) {
                btnBoxes[i].setText("✅ " + rawDesc + "\n(點擊預覽)");
                btnBoxes[i].setBackground(getRoundRect("#dcfce7", 8));
                btnBoxes[i].setTextColor(Color.parseColor("#166534"));
                btnBoxes[i].getPaint().setFakeBoldText(true);
            } else {
                btnBoxes[i].setText("📸 " + rawDesc + "\n(點擊拍照)");
                btnBoxes[i].setBackground(getRoundRect("#e2e8f0", 8));
                btnBoxes[i].setTextColor(Color.parseColor("#475569"));
                btnBoxes[i].getPaint().setFakeBoldText(false);
            }
        }
    }

    void clearAll() {
        for (int i = 0; i < 6; i++) {
            if (photoFiles[i] != null) {
                photoFiles[i].delete();
                photoFiles[i] = null;
            }
        }
        refreshGrid();
        Toast.makeText(this, "已全部清空重來", Toast.LENGTH_SHORT).show();
        writeLog("手動全部清空重來");
    }

    void onGridButtonTouch(int idx) {
        if (photoFiles[idx] != null && photoFiles[idx].exists()) {
            showPreviewDialog(idx, false, null, null, null); 
        } else {
            takePictureForSlot(idx);
        }
    }

    void takePictureForSlot(int idx) {
        targetIndex = idx;
        
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = new File(folder, (targetIndex + 1) + "_" + getDesc(targetIndex).replace("\n", "") + "_" + System.currentTimeMillis() + ".jpg");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            currentPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
        } else {
            currentPhotoUri = Uri.fromFile(photoFile);
        }
        intent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 100 && resultCode == RESULT_OK) {
            File savedFile = null;
            File[] files = folder.listFiles();
            if (files != null) {
                long lastMod = Long.MIN_VALUE;
                String expectedPrefix = (targetIndex + 1) + "_" + getDesc(targetIndex).replace("\n", "");
                for (File f : files) {
                    if (f.getName().startsWith(expectedPrefix) && f.lastModified() > lastMod) {
                        savedFile = f;
                        lastMod = f.lastModified();
                    }
                }
            }
            if (savedFile != null && savedFile.exists()) {
                photoFiles[targetIndex] = savedFile;
                scaleImage(savedFile);
                refreshGrid();
                
                if (autoBackup) {
                    backupSinglePhoto(targetIndex);
                    Toast.makeText(this, "📸 【" + getDesc(targetIndex).replace("\n", "") + "】拍攝並已自動備份！", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "📸 【" + getDesc(targetIndex).replace("\n", "") + "】拍攝完成！", Toast.LENGTH_SHORT).show();
                }
                
                writeLog("相機拍照成功，檔案存至: " + savedFile.getName());
            }
        }
    }

    void scaleImage(File file) {
        if (imgQuality.equals("高畫質")) return;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int outWidth = imgQuality.equals("中畫質") ? 800 : 640;
            int sampleSize = 1;
            while (options.outWidth / sampleSize > outWidth) {
                sampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.close();
        } catch (Exception e) {
            writeLog("縮放照片失敗: " + e.getMessage());
        }
    }

    void backupSinglePhoto(int idx) {
        try {
            File source = photoFiles[idx];
            if (source != null && source.exists()) {
                String timePrefix = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                String pureDesc = getDesc(idx).replace("\n", "");
                File dest = new File(savedFolder, timePrefix + "_" + selectedChemical + "_" + pureDesc + ".jpg");
                copyFile(source, dest);
                writeLog("已在背景自動備份單張照片: " + dest.getName());
            }
        } catch (Exception e) {
            writeLog("自動備份失敗: " + e.getMessage());
        }
    }

    void saveToLocalFolder() {
        int count = 0;
        for (File f : photoFiles) {
            if (f != null && f.exists()) count++;
        }
        if (count == 0) {
            Toast.makeText(this, "⚠️ 請先至少拍一張照片！", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog pd = ProgressDialog.show(this, "處理中", "正在備份照片到專屬圖庫...");
        new Handler().postDelayed(() -> {
            try {
                int savedCount = 0;
                String timePrefix = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                
                for (int i = 0; i < 6; i++) {
                    File source = photoFiles[i];
                    if (source != null && source.exists()) {
                        String pureDesc = getDesc(i).replace("\n", "");
                        File dest = new File(savedFolder, timePrefix + "_" + selectedChemical + "_" + pureDesc + ".jpg");
                        copyFile(source, dest);
                        savedCount++;
                    }
                }
                pd.dismiss();
                Toast.makeText(this, "✅ 成功備份 " + savedCount + " 張照片！\n請點擊上方的「📁 圖庫」查看", Toast.LENGTH_LONG).show();
                writeLog("已成功備份 " + savedCount + " 張照片至專屬目錄");
            } catch (Exception e) {
                pd.dismiss();
                Toast.makeText(this, "❌ 儲存失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 300);
    }
    
    private void copyFile(File source, File dest) throws IOException {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    void sendPhotos() {
        int count = 0;
        for (File f : photoFiles) {
            if (f != null && f.exists()) count++;
        }
        if (count == 0) {
            Toast.makeText(this, "⚠️ 請先至少拍一張照片！", Toast.LENGTH_LONG).show();
            return;
        }
        if (emailAddress.isEmpty()) {
            Toast.makeText(this, "⚠️ 請先設定收件信箱！", Toast.LENGTH_LONG).show();
            return;
        }

        String timeStr = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(new Date());
        ProgressDialog pd = ProgressDialog.show(this, "處理中", "正在準備照片附件...");
        final int finalCount = count;

        new Handler().postDelayed(() -> {
            try {
                ArrayList<Uri> imageUris = new ArrayList<>();
                for (File f : photoFiles) {
                    if (f != null && f.exists()) {
                        Uri fileUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                        } else {
                            fileUri = Uri.fromFile(f);
                        }
                        imageUris.add(fileUri);
                    }
                }
                pd.dismiss();
                String subject = timeStr.replace("_", " ") + " " + selectedChemical + "槽車打藥";
                String body = "您好，\n\n現場已完成「" + selectedChemical + "」槽車作業，共拍攝 " + finalCount + " 張照片，請見附件。\n";

                Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                emailIntent.setType("image/jpeg");
                emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{emailAddress});
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                emailIntent.putExtra(Intent.EXTRA_TEXT, body);
                emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
                emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                
                startActivity(Intent.createChooser(emailIntent, "寄送照片..."));

            } catch (Exception e) {
                pd.dismiss();
                Toast.makeText(this, "❌ 準備附件失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 300);
    }
    
    // ✨ 新增：發送歷史圖庫中勾選的照片 (批次處理)
    void sendHistoricalPhotos(HashSet<String> selectedPaths) {
        if (emailAddress.isEmpty()) {
            Toast.makeText(this, "⚠️ 請先去設定中填寫收件信箱！", Toast.LENGTH_LONG).show();
            return;
        }

        ProgressDialog pd = ProgressDialog.show(this, "處理中", "正在打包選取的照片...");
        
        new Handler().postDelayed(() -> {
            try {
                ArrayList<Uri> imageUris = new ArrayList<>();
                for (String path : selectedPaths) {
                    File f = new File(path);
                    if (f.exists()) {
                        Uri fileUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                        } else {
                            fileUri = Uri.fromFile(f);
                        }
                        imageUris.add(fileUri);
                    }
                }
                pd.dismiss();
                
                String timeStr = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(new Date());
                String subject = timeStr.replace("_", " ") + " 槽車打藥 (歷史備份補寄)";
                String body = "您好，\n\n此為歷史備份圖庫中補寄之照片，共挑選 " + selectedPaths.size() + " 張，請見附件。\n";

                Intent emailIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                emailIntent.setType("image/jpeg");
                emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{emailAddress});
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
                emailIntent.putExtra(Intent.EXTRA_TEXT, body);
                emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, imageUris);
                emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                
                startActivity(Intent.createChooser(emailIntent, "寄送歷史照片..."));

            } catch (Exception e) {
                pd.dismiss();
                Toast.makeText(this, "❌ 準備附件失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 300);
    }
    
    // ✨ 升級：支援多選的折疊圖庫
    void showGalleryDialog(final String expandedDate, final HashSet<String> previousSelections) {
        // 保存使用者的打勾狀態
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
            String dateStr = sdf.format(new Date(f.lastModified()));
            if (!groupedMap.containsKey(dateStr)) {
                groupedMap.put(dateStr, new ArrayList<>());
            }
            groupedMap.get(dateStr).add(f);
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout layList = new LinearLayout(this);
        layList.setOrientation(LinearLayout.VERTICAL);
        layList.setPadding(dpToPx(12), dpToPx(16), dpToPx(12), dpToPx(16));

        final AlertDialog[] dialogRef = new AlertDialog[1]; 

        for (Map.Entry<String, ArrayList<File>> entry : groupedMap.entrySet()) {
            final String dateStr = entry.getKey();
            ArrayList<File> dateFiles = entry.getValue();

            LinearLayout groupLayout = new LinearLayout(this);
            groupLayout.setOrientation(LinearLayout.VERTICAL);
            groupLayout.setPadding(0, 0, 0, dpToPx(12));

            final Button btnHeader = new Button(this);
            boolean isExpanded = dateStr.equals(expandedDate);
            btnHeader.setText("📅 " + dateStr + " (共 " + dateFiles.size() + " 張) " + (isExpanded ? "▲" : "▼"));
            btnHeader.setTextColor(Color.parseColor("#1e293b"));
            btnHeader.setBackground(getRoundRect("#f1f5f9", 8)); 
            btnHeader.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
            btnHeader.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            groupLayout.addView(btnHeader);

            final LinearLayout layContent = new LinearLayout(this);
            layContent.setOrientation(LinearLayout.VERTICAL);
            layContent.setPadding(dpToPx(4), dpToPx(12), 0, dpToPx(8));
            layContent.setVisibility(isExpanded ? View.VISIBLE : View.GONE);

            btnHeader.setOnClickListener(v -> {
                if (layContent.getVisibility() == View.VISIBLE) {
                    layContent.setVisibility(View.GONE);
                    btnHeader.setText("📅 " + dateStr + " (共 " + dateFiles.size() + " 張) ▼");
                } else {
                    layContent.setVisibility(View.VISIBLE);
                    btnHeader.setText("📅 " + dateStr + " (共 " + dateFiles.size() + " 張) ▲");
                }
            });

            for (File f : dateFiles) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, 0, 0, dpToPx(16));

                // ✨ 加入：選取核取方塊
                CheckBox chk = new CheckBox(this);
                chk.setChecked(selections.contains(f.getAbsolutePath()));
                chk.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selections.add(f.getAbsolutePath());
                    } else {
                        selections.remove(f.getAbsolutePath());
                    }
                    // 動態更新寄出按鈕的數量
                    if (dialogRef[0] != null) {
                        Button btnSend = dialogRef[0].getButton(AlertDialog.BUTTON_NEGATIVE);
                        if (btnSend != null) {
                            btnSend.setText("📧 寄出選取 (" + selections.size() + ")");
                        }
                    }
                });
                row.addView(chk);

                ImageView thumb = new ImageView(this);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 16; 
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                thumb.setImageBitmap(bmp);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams lpImg = new LinearLayout.LayoutParams(dpToPx(50), dpToPx(50));
                thumb.setLayoutParams(lpImg);
                
                thumb.setOnClickListener(v -> {
                    dialogRef[0].dismiss();
                    // 傳遞記憶狀態
                    showPreviewDialog(0, true, f, dateStr, selections); 
                });
                row.addView(thumb);

                TextView tvName = new TextView(this);
                String rawName = f.getName().replace(".jpg", "");
                String displayName = rawName;
                String[] parts = rawName.split("_");
                if (parts.length >= 4) {
                    try {
                        String timeStr = parts[1].substring(0, 2) + ":" + parts[1].substring(2, 4);
                        displayName = "🕒 " + timeStr + "\n" + parts[2] + " - " + parts[3];
                    } catch (Exception e) {}
                }
                tvName.setText(displayName);
                tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tvName.setPadding(dpToPx(10), 0, dpToPx(8), 0);
                LinearLayout.LayoutParams lpTv = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                tvName.setLayoutParams(lpTv);
                row.addView(tvName);

                Button btnDel = new Button(this);
                btnDel.setText("🗑️");
                btnDel.setTextColor(Color.WHITE);
                btnDel.setBackground(getRoundRect("#ef4444", 6));
                LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
                btnDel.setLayoutParams(lpBtn);
                btnDel.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                        .setTitle("刪除確認")
                        .setMessage("確定要永久刪除這張備份嗎？")
                        .setPositiveButton("刪除", (d, w) -> {
                            f.delete();
                            selections.remove(f.getAbsolutePath()); // 如果刪除了，也要從勾選清單移除
                            dialogRef[0].dismiss();
                            showGalleryDialog(dateStr, selections); // 刷新圖庫並保持展開
                        })
                        .setNegativeButton("取消", null)
                        .show();
                });
                row.addView(btnDel);

                layContent.addView(row);
            }
            
            groupLayout.addView(layContent);
            layList.addView(groupLayout);
        }

        sv.addView(layList);
        builder.setView(sv);
        
        builder.setPositiveButton("關閉", null);
        builder.setNegativeButton("📧 寄出選取 (" + selections.size() + ")", null); // 稍後設定點擊事件
        
        dialogRef[0] = builder.create();
        
        // 覆寫「寄出選取」按鈕的行為，避免點擊後對話框直接關閉
        dialogRef[0].setOnShowListener(dialogInterface -> {
            Button btnSend = dialogRef[0].getButton(AlertDialog.BUTTON_NEGATIVE);
            btnSend.setTextColor(Color.parseColor("#16a34a")); // 綠色字體特別標示
            btnSend.setOnClickListener(v -> {
                if (selections.isEmpty()) {
                    Toast.makeText(MainActivity.this, "⚠️ 請先勾選要寄出的照片！", Toast.LENGTH_SHORT).show();
                } else {
                    sendHistoricalPhotos(selections);
                }
            });
        });
        
        dialogRef[0].show();
    }

    // ✨ 更新預覽，接收並傳遞多選記憶狀態
    void showPreviewDialog(final int idx, boolean isFromGallery, File specificFile, final String expandedDate, final HashSet<String> selections) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        File fileToPreview = isFromGallery ? specificFile : photoFiles[idx];
        if(fileToPreview == null || !fileToPreview.exists()) return;
        
        if (isFromGallery) {
            builder.setTitle("歷史預覽: " + fileToPreview.getName());
        } else {
            builder.setTitle("預覽: " + getDesc(idx).replace("\n", ""));
        }
        
        LinearLayout lay = new LinearLayout(this);
        lay.setGravity(Gravity.CENTER);
        
        ImageView iv = new ImageView(this);
        
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(fileToPreview.getAbsolutePath(), options);
        int sampleSize = 1;
        while (options.outWidth / sampleSize > 800 || options.outHeight / sampleSize > 800) {
            sampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        Bitmap bmp = BitmapFactory.decodeFile(fileToPreview.getAbsolutePath(), options);
        
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(300));
        lp.setMargins(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        iv.setLayoutParams(lp);
        
        lay.addView(iv);
        builder.setView(lay);
        
        if (isFromGallery) {
            // 返回時，把之前勾選的清單和展開日期帶回去
            builder.setPositiveButton("返回圖庫", (d, w) -> showGalleryDialog(expandedDate, selections));
        } else {
            builder.setMessage("確定要刪除這張照片並重拍嗎？");
            builder.setPositiveButton("🗑️ 刪除重拍", (dialog, which) -> {
                photoFiles[idx].delete();
                photoFiles[idx] = null;
                refreshGrid();
            });
            builder.setNegativeButton("保留", null);
        }
        builder.show();
    }

    void showChemSelectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("選擇藥液類型");
        builder.setItems(chemOptions, (dialog, which) -> {
            if (chemOptions[which].equals("自訂")) {
                showCustomChemDialog();
            } else {
                selectedChemical = chemOptions[which];
                btnChemSelector.setText(selectedChemical + " ▼");
                refreshGrid();
            }
        });
        builder.show();
    }

    void showCustomChemDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("輸入其他藥液名稱");
        final EditText input = new EditText(this);
        input.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));
        builder.setView(input);
        builder.setPositiveButton("確定", (dialog, which) -> {
            if (!input.getText().toString().isEmpty()) {
                selectedChemical = input.getText().toString();
                btnChemSelector.setText(selectedChemical + " ▼");
                refreshGrid();
            }
        });
        builder.show();
    }

    void showSettings() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ 設定");
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        
        TextView lblEmail = new TextView(this);
        lblEmail.setText("收件者 Email:");
        lay.addView(lblEmail);
        
        final EditText edtEmail = new EditText(this);
        edtEmail.setText(emailAddress);
        lay.addView(edtEmail);
        
        CheckBox chkDefaultEmail = new CheckBox(this);
        chkDefaultEmail.setText("使用預設信箱 (范光明)");
        chkDefaultEmail.setPadding(0, dpToPx(8), 0, dpToPx(16));
        chkDefaultEmail.setChecked(emailAddress.equals("kuangming.fan@tci.com.tw"));
        chkDefaultEmail.setOnCheckedChangeListener((btnView, isChecked) -> {
            if (isChecked) {
                edtEmail.setText("kuangming.fan@tci.com.tw");
            } else if (edtEmail.getText().toString().equals("kuangming.fan@tci.com.tw")) {
                edtEmail.setText("");
            }
        });
        lay.addView(chkDefaultEmail);

        CheckBox chkAutoBackup = new CheckBox(this);
        chkAutoBackup.setText("拍照後自動備份至圖庫");
        chkAutoBackup.setPadding(0, 0, 0, dpToPx(16));
        chkAutoBackup.setChecked(autoBackup);
        chkAutoBackup.setOnCheckedChangeListener((btnView, isChecked) -> {
            autoBackup = isChecked;
        });
        lay.addView(chkAutoBackup);
        
        TextView lblQ = new TextView(this);
        lblQ.setText("照片壓縮畫質:");
        lay.addView(lblQ);
        
        final Spinner spinQ = new Spinner(this);
        String[] qs = {"高畫質", "中畫質", "低畫質"};
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qs);
        spinQ.setAdapter(adp);
        spinQ.setSelection(Arrays.asList(qs).indexOf(imgQuality));
        lay.addView(spinQ);
        
        TextView lblDays = new TextView(this);
        lblDays.setText("圖庫備份自動清理:");
        lblDays.setPadding(0, dpToPx(16), 0, 0);
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
        
        builder.setView(lay);
        builder.setPositiveButton("儲存", (dialog, which) -> {
            emailAddress = edtEmail.getText().toString();
            imgQuality = qs[spinQ.getSelectedItemPosition()];
            autoDeleteDays = daysValues[spinDays.getSelectedItemPosition()];
            
            prefs.edit()
                 .putString("TargetEmail", emailAddress)
                 .putString("ImgQuality", imgQuality)
                 .putInt("AutoDeleteDays", autoDeleteDays)
                 .putBoolean("AutoBackup", autoBackup) 
                 .apply();
                 
            Toast.makeText(this, "✅ 設定已儲存！", Toast.LENGTH_SHORT).show();
            cleanUpOldPhotos(); 
        });
        builder.show();
    }

    void showLogDialog() {
        String content = "目前沒有任何日誌記錄。";
        if (logFile.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(logFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                if (sb.length() > 0) content = sb.toString();
            } catch (Exception e) {}
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📜 內部執行日誌");
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
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
}