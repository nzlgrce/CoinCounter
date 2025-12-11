package com.example.moneycounter;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ContentValues;
import android.provider.MediaStore;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;



import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 300;
    private ActivityResultLauncher<Intent> cameraLauncher;
    private Uri cameraImageUri;
    private Button btnTakePicture;

    private static final int PICK_IMAGE_REQUEST = 200;
    private ImageView selectedImage, overlayView;
    private TextView txtTotalValue;
    private Button btnUpload, btnStartScan;

    private Bitmap uploadedBitmap;

    // Single thread for API requests
    private ExecutorService apiExecutor = Executors.newSingleThreadExecutor();

    // OkHttp client with longer timeout
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static final String API_KEY = "RQW767Vtdja8lvZ3oOcB";
    private static final String MODEL_URL = "https://detect.roboflow.com/final-coin-detector-ml/5";

    private final HashMap<String, Double> coinValues = new HashMap<String, Double>() {{
        put("1_piso", 1.0);
        put("5_peso", 5.0);
        put("10_peso", 10.0);
        put("20_peso", 20.0);
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        selectedImage = findViewById(R.id.selectedImage);
        overlayView = findViewById(R.id.overlayView);
        txtTotalValue = findViewById(R.id.txtTotalValue);
        btnStartScan = findViewById(R.id.btnStartScan);
        btnUpload = findViewById(R.id.btnUploadImage);

        btnUpload.setOnClickListener(v -> {
            if (!hasPermissions()) {
                requestPermissionsNow();
                return;
            }
                openImagePicker();
        });
        btnTakePicture = findViewById(R.id.btnTakePicture);

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        try {
                            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), cameraImageUri);
                            uploadedBitmap = bitmap;
                            selectedImage.setImageBitmap(bitmap);

                            overlayView.setImageBitmap(null);
                            txtTotalValue.setText("₱0.00");

                            Toast.makeText(this, "Image captured!", Toast.LENGTH_SHORT).show();

                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        btnTakePicture.setOnClickListener(v -> {
            if (!hasPermissions()) {
                requestPermissionsNow();
                return;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "coin_capture");
            values.put(MediaStore.Images.Media.DESCRIPTION, "Captured for coin detection");

            cameraImageUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
            );

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);

            cameraLauncher.launch(intent);
        });

        btnStartScan.setOnClickListener(v -> scanUploadedImage());
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();

            try {
                InputStream inputStream = getContentResolver().openInputStream(imageUri);
                uploadedBitmap = BitmapFactory.decodeStream(inputStream);
                selectedImage.setImageBitmap(uploadedBitmap);

                // Clear previous overlay and total
                overlayView.setImageBitmap(null);
                txtTotalValue.setText("₱0.00");

                Toast.makeText(this, "Image uploaded!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void scanUploadedImage() {
        if (uploadedBitmap == null) {
            Toast.makeText(this, "Please upload an image first", Toast.LENGTH_SHORT).show();
            return;
        }

        runOnUiThread(() -> findViewById(R.id.progressBar).setVisibility(View.VISIBLE));

        apiExecutor.execute(() -> {
            try {
                // Resize image for faster upload
                int targetSize = 512;
                Bitmap smallBitmap = Bitmap.createScaledBitmap(uploadedBitmap, targetSize, targetSize, true);

                JSONArray predictions = sendToRoboflow(smallBitmap);

                HashMap<String, Integer> counts = new HashMap<>();
                double total = 0.0;

                for (int i = 0; i < predictions.length(); i++) {
                    JSONObject pred = predictions.getJSONObject(i);
                    String label = mapLabel(pred.getString("class"));
                    if (label != null) {
                        counts.put(label, counts.getOrDefault(label, 0) + 1);
                        total += coinValues.get(label);
                    }
                }

                double finalTotal = total;

                // Prepare overlay bitmap matching original image size
                Bitmap overlayBitmap = uploadedBitmap.copy(Bitmap.Config.ARGB_8888, true);
                Canvas canvas = new Canvas(overlayBitmap);

                Paint boxPaint = new Paint();
                boxPaint.setStyle(Paint.Style.STROKE);
                boxPaint.setStrokeWidth(5);
                boxPaint.setColor(Color.GREEN);

                Paint textPaint = new Paint();
                textPaint.setColor(Color.RED);
                textPaint.setTextSize(Math.max(overlayBitmap.getWidth(), overlayBitmap.getHeight()) / 40f);

                // Scaling factors
                float scaleX = (float) uploadedBitmap.getWidth() / targetSize;
                float scaleY = (float) uploadedBitmap.getHeight() / targetSize;

                // Keep track of label positions to avoid overlap
                HashMap<String, Integer> labelOffsets = new HashMap<>();

                for (int i = 0; i < predictions.length(); i++) {
                    JSONObject pred = predictions.getJSONObject(i);

                    float x = (float) pred.getDouble("x") * scaleX;
                    float y = (float) pred.getDouble("y") * scaleY;
                    float w = (float) pred.getDouble("width") * scaleX;
                    float h = (float) pred.getDouble("height") * scaleY;

                    canvas.drawRect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, boxPaint);

                    String label = mapLabel(pred.getString("class"));
                    if (label != null) {
                        // Offset labels to prevent overlapping
                        int offset = labelOffsets.getOrDefault(label, 0);
                        canvas.drawText(label, x - w / 2, y - h / 2 - 10 - offset, textPaint);
                        labelOffsets.put(label, offset + 50); // increase offset for next same label
                    }
                }

                runOnUiThread(() -> {
                    overlayView.setImageBitmap(overlayBitmap);

                    StringBuilder sb = new StringBuilder();
                    counts.forEach((coin, count) -> sb.append(coin).append(": ").append(count).append("\n"));
                    sb.append("Total: ₱").append(String.format("%.2f", finalTotal));
                    txtTotalValue.setText(sb.toString());

                    findViewById(R.id.progressBar).setVisibility(View.GONE);
                });

            } catch (Exception e) {
                e.printStackTrace();
                String error = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to analyze: " + error, Toast.LENGTH_LONG).show();
                    findViewById(R.id.progressBar).setVisibility(View.GONE);
                });
            }
        });
    }


    private JSONArray sendToRoboflow(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos); // higher compression
        byte[] imgBytes = baos.toByteArray();

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "upload.jpg",
                        RequestBody.create(imgBytes, MediaType.parse("image/jpeg")))
                .build();

        Request req = new Request.Builder()
                .url(MODEL_URL + "?api_key=" + API_KEY)
                .post(body)
                .build();

        Response res = httpClient.newCall(req).execute();
        String responseBody = res.body() != null ? res.body().string() : null;

        if (!res.isSuccessful()) {
            throw new Exception("Roboflow error: " + responseBody);
        }

        JSONObject json = new JSONObject(responseBody);
        return json.getJSONArray("predictions");
    }

    // Updated mapLabel() to handle different class names
    private String mapLabel(String label) {
        label = label.toLowerCase();
        switch (label) {
            case "1_peso":
            case "1_piso":
                return "1_piso";
            case "5_peso":
            case "5_pesos":
            case "5peso":
                return "5_peso";
            case "10_peso":
            case "10_pesos":
            case "10peso":
                return "10_peso";
            case "20_peso":
            case "20_pesos":
            case "20peso":
                return "20_peso";
        }
        return null;
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                },
                PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission required to use camera!", Toast.LENGTH_LONG).show();
            }
        }
    }

}
