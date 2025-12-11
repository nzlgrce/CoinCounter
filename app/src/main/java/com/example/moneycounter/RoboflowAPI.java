package com.example.moneycounter;

import android.util.Base64;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import okhttp3.*;

public class RoboflowAPI {

    private static final String API_KEY = "RQW767Vtdja8lvZ3oOcB";
    private static final String MODEL_URL = "https://detect.roboflow.com/final-coin-detector-ml/3";

    private static final OkHttpClient client = new OkHttpClient();

    public interface Callback {
        void onResult(JSONArray predictions);
        void onError(Exception e);
    }

    public static void detect(Bitmap bitmap, Callback callback) {
        try {
            // Convert bitmap to JPEG and then Base64
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

            // Build multipart request
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("api_key", API_KEY)
                    .addFormDataPart("image", "image.jpg",
                            RequestBody.create(baos.toByteArray(), MediaType.parse("image/jpeg")))
                    .build();

            Request request = new Request.Builder()
                    .url(MODEL_URL)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onError(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        String json = response.body().string();
                        JSONObject obj = new JSONObject(json);
                        JSONArray predictions = obj.getJSONArray("predictions");
                        callback.onResult(predictions);
                    } catch (Exception e) {
                        callback.onError(e);
                    }
                }
            });
        } catch (Exception e) {
            callback.onError(e);
        }
    }
}
