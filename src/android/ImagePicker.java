package com.spoon.imagepicker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImagePicker extends CordovaPlugin {
    private static final String ACTION_GET_PICTURES = "getPictures";
    private static final String ACTION_HAS_READ_PERMISSION = "hasReadPermission";
    private static final String ACTION_REQUEST_READ_PERMISSION = "requestReadPermission";

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SELECT_PICTURE = 200;

    private static final String FILE_ACCESS_ERROR = "Cannot access file. (-1)";

    private CallbackContext callbackContext;
    private int maxImageCount;
    private int maxPhotoSize;
    private int maxVideoSize;
    private LinearLayout layout = null;

   public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
    
        if (ACTION_HAS_READ_PERMISSION.equals(action)) {
            callbackContext.sendPluginResult(
                new PluginResult(
                    PluginResult.Status.OK,
                    hasReadPermission()
                )
            );
            return true;
    
        } else if (ACTION_REQUEST_READ_PERMISSION.equals(action)) {
            requestReadPermission();
            return true;
    
        } else if (ACTION_GET_PICTURES.equals(action)) {
            final JSONObject params = args.getJSONObject(0);
    
            this.maxImageCount = params.has("maximumImagesCount")
                ? params.getInt("maximumImagesCount")
                : 20;
    
            this.maxPhotoSize = params.has("maxPhotoSize")
                ? params.getInt("maxPhotoSize")
                : 15;
    
            this.maxVideoSize = params.has("maxVideoSize")
                ? params.getInt("maxVideoSize")
                : 5;
    
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2) {
    
                int deviceMaxLimit = MediaStore.getPickImagesMaxLimit();
    
                if (this.maxImageCount > deviceMaxLimit) {
                    this.maxImageCount = deviceMaxLimit;
                    this.showMaxLimitWarning(deviceMaxLimit);
                }
            }
    
            boolean useFilePicker =
                params.has("useFilePicker")
                    && params.getBoolean("useFilePicker");
    
            boolean allowVideo =
                params.has("allow_video")
                    && params.getBoolean("allow_video");
    
            List<String> mimeTypes = new ArrayList<>();
            mimeTypes.add("image/*");
    
            if (allowVideo) {
                mimeTypes.add("video/*");
            }
    
            Intent imagePickerIntent;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowVideo) {
            
                imagePickerIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            
                imagePickerIntent.setType("image/*");
            
                imagePickerIntent.putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_MAX,
                    this.maxImageCount
                );
            
                Log.d(
                    "ImagePicker",
                    "PHOTO PICKER MULTI: max=" + this.maxImageCount
                );
            
            } else {
            
                imagePickerIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            
                imagePickerIntent.setType("*/*");
            
                imagePickerIntent.putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    mimeTypes.toArray(new String[0])
                );
            
                imagePickerIntent.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    true
                );
            
                imagePickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
            
                Log.d(
                    "ImagePicker",
                    "DOCUMENT PICKER MULTI: max=" + this.maxImageCount
                );
            }

            Log.d("ImagePicker", "=== PICKER START ===");
            Log.d("ImagePicker", "ACTION = " + imagePickerIntent.getAction());
            Log.d("ImagePicker", "TYPE = " + imagePickerIntent.getType());
            Log.d("ImagePicker", "MAX = " +
                    imagePickerIntent.getIntExtra(
                            MediaStore.EXTRA_PICK_IMAGES_MAX,
                            -1
                    ));
            Log.d("ImagePicker", "SDK = " + Build.VERSION.SDK_INT);
            Log.d("ImagePicker", "Intent = " + imagePickerIntent);
                
            cordova.startActivityForResult(
                this,
                imagePickerIntent,
                SELECT_PICTURE
            );
    
            this.showMaxFileSizeWarning(allowVideo);
    
            return true;
        }
    
        return false;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode != SELECT_PICTURE) {
            return;
        }
    
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (resultCode == Activity.RESULT_CANCELED) {
                callbackContext.success(new JSONArray());
            } else {
                callbackContext.error("No images selected");
            }
            return;
        }
    
        // Loader auf UI-Thread anzeigen
        cordova.getActivity().runOnUiThread(() -> {
            showLoader();
        });
    
        ExecutorService executor = Executors.newSingleThreadExecutor();
    
        executor.execute(() -> {
    
            boolean sizeLimitExceeded = false;
    
            try {
    
                ArrayList<String> fileURIs = new ArrayList<>();
    
                /*
                 * ==========================================
                 * MEHRFACHAUSWAHL
                 * ==========================================
                 */
    
                ClipData clipData = data.getClipData();
    
                if (clipData != null && clipData.getItemCount() > 0) {
    
                    int count = Math.min(
                        clipData.getItemCount(),
                        ImagePicker.this.maxImageCount
                    );
    
                    Log.d(
                        "ImagePicker",
                        "MEHRFACHAUSWAHL: " + count + " Bilder"
                    );
    
                    for (int i = 0; i < count; i++) {
    
                        Uri uri = clipData.getItemAt(i).getUri();
    
                        Log.d(
                            "ImagePicker",
                            "Bild " + (i + 1) + "/" + count + ": " + uri
                        );
    
                        if (uri == null) {
                            continue;
                        }
    
                        boolean isVideo = this.isVideo(uri);
    
                        int maxFileSize = isVideo
                            ? this.maxVideoSize
                            : this.maxPhotoSize;
    
                        double size = this.getFileSizeFromUri(uri);
    
                        Log.d(
                            "ImagePicker",
                            "Bild " + (i + 1) + " Größe: " + size + " MB"
                        );
    
                        if (size > maxFileSize) {
                            sizeLimitExceeded = true;
                            continue;
                        }
    
                        String path =
                            ImagePicker.this.copyFileToInternalStorage(
                                uri,
                                ""
                            );
    
                        Log.d(
                            "ImagePicker",
                            "Bild " + (i + 1) + " kopiert nach: " + path
                        );
    
                        if (path == null || path.equals("-1")) {
                            callbackContext.error(FILE_ACCESS_ERROR);
                            return;
                        }
    
                        fileURIs.add(path);
                    }
    
                /*
                 * ==========================================
                 * EINZELAUSWAHL
                 * ==========================================
                 */
    
                } else if (data.getData() != null) {
    
                    Uri uri = data.getData();
    
                    Log.d(
                        "ImagePicker",
                        "EINZELAUSWAHL: " + uri
                    );
    
                    boolean isVideo = this.isVideo(uri);
    
                    int maxFileSize = isVideo
                        ? this.maxVideoSize
                        : this.maxPhotoSize;
    
                    double size = this.getFileSizeFromUri(uri);
    
                    if (size > maxFileSize) {
    
                        sizeLimitExceeded = true;
    
                    } else {
    
                        String path =
                            ImagePicker.this.copyFileToInternalStorage(
                                uri,
                                ""
                            );
    
                        if (path == null || path.equals("-1")) {
                            callbackContext.error(FILE_ACCESS_ERROR);
                            return;
                        }
    
                        fileURIs.add(path);
                    }
    
                } else {
    
                    callbackContext.error("No images selected");
                    return;
                }
    
                /*
                 * ==========================================
                 * ERGEBNIS ERSTELLEN
                 * ==========================================
                 */
    
                ArrayList<JSONObject> imageInfos =
                    new ArrayList<>();
    
                for (int i = 0; i < fileURIs.size(); i++) {
    
                    String fileUri = fileURIs.get(i);
    
                    Log.d(
                        "ImagePicker",
                        "Verarbeite Ergebnis " +
                        (i + 1) +
                        "/" +
                        fileURIs.size() +
                        ": " +
                        fileUri
                    );
    
                    boolean isVideo = this.isVideo(fileUri);
    
                    JSONObject json = new JSONObject();
    
                    json.put("path", fileUri);
                    json.put("isVideo", isVideo);
    
                    if (!isVideo) {
    
                        BitmapFactory.Options options =
                            new BitmapFactory.Options();
    
                        options.inJustDecodeBounds = true;
    
                        Uri imageUri = Uri.parse(fileUri);
    
                        String imagePath = imageUri.getPath();
    
                        if (imagePath != null) {
    
                            BitmapFactory.decodeFile(
                                imagePath,
                                options
                            );
    
                            json.put("width", options.outWidth);
                            json.put("height", options.outHeight);
                        }
    
                    } else {
    
                        json.put("width", 0);
                        json.put("height", 0);
    
                        File videoFile = new File(
                            Uri.parse(fileUri).getPath()
                        );
    
                        String videoThumbnail =
                            this.generateVideoThumbnail(videoFile);
    
                        json.put(
                            "thumbnail",
                            videoThumbnail
                        );
                    }
    
                    imageInfos.add(json);
                }
    
                JSONArray result =
                    new JSONArray(imageInfos);
    
                Log.d(
                    "ImagePicker",
                    "================================="
                );
    
                Log.d(
                    "ImagePicker",
                    "ANZAHL ERGEBNISSE: " +
                    result.length()
                );
    
                Log.d(
                    "ImagePicker",
                    "ERGEBNIS: " +
                    result.toString()
                );
    
                if (sizeLimitExceeded) {
                    this.showMaxFileSizeExceededWarning();
                }
    
                callbackContext.success(result);
    
            } catch (Exception e) {
    
                Log.e(
                    "ImagePicker",
                    "Unexpected error",
                    e
                );
    
                callbackContext.error(
                    "Unexpected error: " + e.getMessage()
                );
    
            } finally {
    
                cordova.getActivity().runOnUiThread(() -> {
                    hideLoader();
                });
    
                executor.shutdown();
            }
        });
    }

    private void showLoader() {
        Context context = cordova.getActivity().getApplicationContext();
        this.layout = new LinearLayout(context);
        this.layout.setGravity(Gravity.CENTER);
        this.layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
        this.layout.setBackgroundColor(Color.parseColor("#A6000000"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        );
        cordova.getActivity().addContentView(layout, params);
        cordova.getActivity().getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        );
        ProgressBar loader = new ProgressBar(context);
        this.layout.addView(loader);
    }

    private void hideLoader() {
        if (this.layout != null) {
            this.layout.removeAllViews();
            ((ViewGroup) this.layout.getParent()).removeView(layout);
            this.layout = null;
            cordova.getActivity().getWindow()
              .clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    /**
    * Choosing a picture launches another Activity, so we need to implement the
    * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
    * before we get the launched Activity's result.
    *
    * @see http://cordova.apache.org/docs/en/dev/guide/platforms/android/plugin.html#launching-other-activities
    */
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
    }

    public boolean isVideo(String filePath) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(filePath);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                extension != null ? extension.toLowerCase() : null
        );
        return mime != null && mime.startsWith("video/");
    }

    public boolean isVideo(Uri uri) {
        String mime = cordova.getActivity().getContentResolver().getType(uri);
        return mime != null && mime.startsWith("video/");
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        // For now we just have one permission, so things can be kept simple...
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            callbackContext.success(1);
        } else {
            callbackContext.success(0);
        }
    }

    @SuppressLint("InlinedApi")
    private boolean hasReadPermission() {
        return Build.VERSION.SDK_INT < 23 ||
          PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) ||
                PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.READ_MEDIA_IMAGES);
    }

    @SuppressLint("InlinedApi")
    private void requestReadPermission() {
        if (!hasReadPermission()) {
            if (Build.VERSION.SDK_INT < 33) {
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            } else {
                cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, new String[]{Manifest.permission.READ_MEDIA_IMAGES});
            }
            return;
        }
        callbackContext.success(1);
    }

    private String copyFileToInternalStorage(Uri uri, String newDirName) {
        Uri returnUri = uri;
        Cursor returnCursor = null;
        try {
            returnCursor = cordova.getActivity().getContentResolver().query(
                returnUri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
            );
        } catch (SecurityException se) {
            (Toast.makeText(cordova.getContext(), FILE_ACCESS_ERROR, Toast.LENGTH_LONG)).show();
            Log.d("error", se.getMessage());
            return "-1";
        }

        int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        returnCursor.moveToFirst();
        String name = returnCursor.getString(nameIndex);

        File output;
        if (!newDirName.equals("")) {
            File dir = new File(cordova.getContext().getFilesDir() + "/" + newDirName);
            if (!dir.exists()) {
                dir.mkdir();
            }
            output = new File(cordova.getContext().getFilesDir() + "/" + newDirName + "/" + name);
        } else {
            output = new File(cordova.getContext().getFilesDir() + "/" + name);
        }
        try {
            InputStream inputStream = cordova.getActivity().getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(output);
            int read = 0;
            int bufferSize = 1024;
            final byte[] buffers = new byte[bufferSize];
            while ((read = inputStream.read(buffers)) != -1) {
                outputStream.write(buffers, 0, read);
            }
            inputStream.close();
            outputStream.close();

        } catch (Exception e) {
            Log.e("copyToInternalStorage ", e.getMessage());
        }

        return output.getPath();
    }

    private double getFileSizeFromUri(Uri uri) {
        double sizeBytes = 0;
        Cursor cursor = cordova.getActivity().getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
        );
        if (cursor != null) {
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            cursor.moveToFirst();
            sizeBytes = cursor.getLong(sizeIndex);
            cursor.close();
        }

        // Convert bytes → MB
        return sizeBytes / (1024.0 * 1024.0);
    }

    private String generateVideoThumbnail(File videoFile) {
        String thumbnailUri = "";
        if (cordova.getContext() == null) { return thumbnailUri; }

        String filename = "video_thumb_" + UUID.randomUUID().toString() + ".jpg";
        File thumbnail = new File(cordova.getContext().getFilesDir(), filename);
        Bitmap bitmap = null;
        Size size = null;

        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(String.valueOf(videoFile));

            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            if (widthStr != null && heightStr != null) {
                int videoWidth = Integer.parseInt(widthStr);
                int videoHeight = Integer.parseInt(heightStr);
                size = new Size(videoWidth, videoHeight);
            }
            try {
                retriever.release();
            } catch (IOException e) {
                Log.e("ImagePicker", "generateVideoThumbnail: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e("ImagePicker", "generateVideoThumbnail: " + e.getMessage());
        }

        if (size == null) {
            size = new Size(500, 500);
        }

        try {
            bitmap = ThumbnailUtils.createVideoThumbnail(videoFile, size, null);
        } catch (IOException e) {
            bitmap = generateColoredBitmap(size, Color.DKGRAY);
        }
        try (FileOutputStream out = new FileOutputStream(thumbnail)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
            out.flush();
        } catch (Exception e) {
            return thumbnailUri;
        }

        thumbnailUri = Uri.fromFile(thumbnail).toString();
        return thumbnailUri;
    }

    public Bitmap generateColoredBitmap(Size size, int color) {
        Bitmap bitmap = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);

        return bitmap;
    }

    private void showMaxLimitWarning(boolean useFilePicker) {
        String toastMsg = "You can only select up to " + this.maxImageCount + " image(s)";
        if (useFilePicker) {
            toastMsg = "Only the first " + this.maxImageCount + " image(s) selected will be taken.";
        }
        (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show();
    }

    private void showMaxLimitWarning(int deviceMaxLimit) {
        String toastMsg = "The maximumImagesCount:" + this.maxImageCount +
                " is greater than the device's max limit of images that can be selected from the MediaStore: " + deviceMaxLimit +
                ". Maximum number of images that can be selected is: " + deviceMaxLimit;

        (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show();
    }

    private void showMaxFileSizeWarning(boolean allowVideo) {
        String toastMsg = allowVideo ? "Image size limit is " + this.maxPhotoSize + "MB\nVideo size limit is " + this.maxVideoSize + "MB"
                : "Image size limit is " + this.maxPhotoSize + "MB";
        cordova.getActivity().runOnUiThread(() -> (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show());
    }

    private void showMaxFileSizeExceededWarning() {
        String toastMsg = "Media(s) above max limit not selected";
        cordova.getActivity().runOnUiThread(() -> (Toast.makeText(cordova.getContext(), toastMsg, Toast.LENGTH_LONG)).show());
    }
}
