package io.anyline.cordova;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Base64OutputStream;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//import androidx.core.content.ContextCompat;
import android.support.v4.content.ContextCompat;
import at.nineyards.anyline.camera.CameraController;
import at.nineyards.anyline.camera.CameraOpenListener;
import at.nineyards.anyline.core.LicenseException;
import at.nineyards.anyline.models.AnylineImage;
import at.nineyards.anyline.util.TempFileUtil;
import io.anyline.plugin.ScanResult;
import io.anyline.plugin.document.DocumentScanResultListener;
import io.anyline.plugin.document.DocumentScanViewPlugin;
import io.anyline.view.LicenseKeyExceptionListener;
import io.anyline.view.ScanView;

/**
 * Example activity for the Anyline-Document-Detection-Module
 */
public class Document4Activity extends AnylineBaseActivity implements CameraOpenListener, LicenseKeyExceptionListener {

    private static final long ERROR_MESSAGE_DELAY = 2000;
    private static final String TAG = Document4Activity.class.getSimpleName();
    private ScanView documentScanView;
    private Toast notificationToast;
    private ImageView imageViewResult;
    private ProgressDialog progressDialog;
    private List<PointF> lastOutline;
    private ObjectAnimator errorMessageAnimator;
    private FrameLayout errorMessageLayout;
    private TextView errorMessage;
    private long lastErrorRecieved = 0;
    private int quality = 100;
    private Runnable errorMessageCleanup;
    private ImageButton btnCapture;
    JSONObject jsonResult;

    private android.os.Handler handler = new android.os.Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_scan_document", "layout", getPackageName()));


        // takes care of fading the error message out after some time with no error reported from the SDK
        errorMessageCleanup = new Runnable() {
            @Override
            public void run() {
                if (Document4Activity.this.isFinishing()) {
                    return;
                }
                if (System.currentTimeMillis() > lastErrorRecieved + ERROR_MESSAGE_DELAY) {
                    if (errorMessage == null || errorMessageAnimator == null) {
                        return;
                    }
                    if (errorMessage.getAlpha() == 0f) {
                        errorMessage.setText("");
                    } else if (!errorMessageAnimator.isRunning()) {
                        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", errorMessage.getAlpha(), 0f);
                        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
                        errorMessageAnimator.setInterpolator(new AccelerateInterpolator());
                        errorMessageAnimator.start();
                    }
                }
                handler.postDelayed(errorMessageCleanup, ERROR_MESSAGE_DELAY);

            }
        };

        // Set the flag to keep the screen on (otherwise the screen may go dark during scanning)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imageViewResult = (ImageView) findViewById(getResources().getIdentifier("image_result", "id", getPackageName()));
        errorMessageLayout = (FrameLayout) findViewById(getResources().getIdentifier("error_message_layout", "id", getPackageName()));
        errorMessage = (TextView) findViewById(getResources().getIdentifier("error_message", "id", getPackageName()));

        documentScanView = (ScanView) findViewById(getResources().getIdentifier("document_scan_view", "id", getPackageName()));
        // add a camera open listener that will be called when the camera is opened or an error occurred
        // this is optional (if not set a RuntimeException will be thrown if an error occurs)
        documentScanView.setCameraOpenListener(this);
        // the view can be configured via a json file in the assets, and this config is set here

        JSONObject json = null;
        try {
            json = new JSONObject(configJson);
            documentScanView.setScanConfig(json, licenseKey);
        } catch (Exception e) {
            e.printStackTrace();
            Log.i(TAG, "**************** Exception: " + e);
        }

        btnCapture = findViewById(getResources().getIdentifier("capture", "id", getPackageName()));

        // get Document specific Configs
        if (json.has("document")) {
            try {
                // Get the Document specific Config
                JSONObject documentConfig = json.getJSONObject("document");

                // set manual capture Button Config
                if (documentConfig.has("manualCaptureButton")) {
                    handler.postDelayed(new Runnable(){
                        @Override
                        public void run(){

                            btnCapture.setColorFilter(Color.parseColor("#C0C0C0"));

                            // init Manual Capture Button
                            btnCapture.setVisibility(View.VISIBLE);
                            btnCapture.setOnClickListener(new View.OnClickListener() {

                                @Override
                                public void onClick(View v) {
                                    btnCapture.setClickable(false);
                                    btnCapture.setVisibility(View.GONE);
                                    documentScanView.stop();
                                    ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).triggerPictureCornerDetection();
                                }
                            });
                        }
                    }, 5000);
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        }

        // initialize Anyline with the license key and a Listener that is called if a result is found
        documentScanView.getScanViewPlugin().addScanResultListener(new DocumentScanResultListener() {
            @Override
            public void onResult(ScanResult documentResult) {

                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                btnCapture.setClickable(false);

                AnylineImage transformedImage = (AnylineImage) documentResult.getResult();

                // resize display view based on larger side of document, and display document
                Log.d(TAG, "onResult: Resize display view based on larger side of document, and display document");
                int widthDP, heightDP;
                Bitmap bmpTransformedImage = transformedImage.getBitmap();

                if (bmpTransformedImage.getHeight() > bmpTransformedImage.getWidth()) {
                    widthDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100, getResources().getDisplayMetrics());
                    heightDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160, getResources().getDisplayMetrics());
                    //Add a comment to this line

                    imageViewResult.getLayoutParams().width = widthDP;
                    imageViewResult.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                } else {
                    widthDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160, getResources().getDisplayMetrics());
                    heightDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100, getResources().getDisplayMetrics());

                    imageViewResult.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    imageViewResult.getLayoutParams().height = heightDP;
                }

                imageViewResult.setImageBitmap(Bitmap.createScaledBitmap(transformedImage.getBitmap(), widthDP, heightDP, false));

                /**
                 * IMPORTANT: cache provided frames here, and release them at the end of this onResult. Because
                 * keeping them in memory (e.g. setting the full frame to an ImageView)
                 * will result in a OutOfMemoryError soon. This error is reported in {@link #onTakePictureError
                 * (Throwable)}
                 *
                 * Use a DiskCache http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html#disk-cache
                 * for example
                 *
                 */
                File outDir = new File(getCacheDir(), "ok");
                outDir.mkdir();
                // change the file ending to png if you want a png
                //JSONObject
                jsonResult = new JSONObject();
                String result = new String();
                try {
                    // convert the transformed image into a gray scaled image internally
                    // transformedImage.getGrayCvMat(false);
                    // get the transformed image as bitmap
                    // Bitmap bmp = transformedImage.getBitmap();
                    // save the image with quality 100 (only used for jpeg, ignored for png)
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this,
                                                                           UUID.randomUUID().toString(), ".jpg");
                    transformedImage.save(imageFile, quality);
                    //showToast(getString(getResources().getIdentifier("document_image_saved_to", "string", getPackageName())) + " " + imageFile.getAbsolutePath());

                    jsonResult.put("imagePath", imageFile.getAbsolutePath());


                    FileInputStream fis = new FileInputStream(imageFile);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    Base64OutputStream base64out = new Base64OutputStream(baos, Base64.NO_WRAP);
                    byte[]buffer = new byte[1024];
                    int len = 0;
                    while ((len = fis.read(buffer)) >= 0) {
                        base64out.write(buffer, 0, len);
                    }
                    base64out.flush();
                    base64out.close();
                    /*
                     * Why should we close Base64OutputStream before processing the data:
                     * http://stackoverflow.com/questions/24798745/androidfiletobase64usingstreamingsometimesmissed2bytes
                     */

                    byte[]data = baos.toByteArray();

                    // Apply contrast
                    byte contrast = (byte)10;
                    int factor = (259 * (contrast + 255)) / (255 * (259 - contrast));
                    byte byteFactor = (byte)factor;
                    int inbetween;
                    int dataCounter = 0;
                    int dataLength = data.length;
                    while (dataCounter < dataLength) {
                        inbetween = factor * (data[dataCounter] - 128) + 128;
                        data[dataCounter] = (byte)inbetween;
                        inbetween = factor * (data[dataCounter + 1] - 128) + 128;
                        data[dataCounter + 1] = (byte)inbetween;
                        inbetween = factor * (data[dataCounter + 2] - 128) + 128;
                        data[dataCounter + 2] = (byte)inbetween;
                        dataCounter += 4;
                    }

                    result = new String(data, "UTF-8");

                    baos.close();
                    fis.close();
                    /**
                     * IMPORTANT: cache provided frames here, and release them at the end of this onResult. Because
                     * keeping them in memory (e.g. setting the full frame to an ImageView)
                     * will result in a OutOfMemoryError soon. This error is reported in {@link #onTakePictureError
                     * (Throwable)}
                     *
                     * Use a DiskCache http://developer.android.com/training/displayingbitmaps/cachebitmap.html#diskcache
                     * for example
                     *
                     */

                    // Put outline and conficence to result
                    jsonResult.put("imageData", result);
                    jsonResult.put("takenManual", "false");
                } catch(Exception e) {
                    String exceptionMessage = e.getMessage();
                    showToast(exceptionMessage);
                    try {
                        jsonResult.put("imageData", "Error: " + exceptionMessage);

                    } catch (Exception je) {
                        Log.e(TAG, "Error while putting image data to json.", je);
                    }
                }

                // release the images
                transformedImage.release();

                ResultReporter.onResult(jsonResult, true);
                setResult(AnylinePlugin.RESULT_OK);
                finish();
            }


            @Override
            public void onPreviewProcessingSuccess(AnylineImage anylineImage) {
                // this is called after the preview of the document is completed, and a full picture will be
                // processed automatically
            }

            @Override
            public void onPreviewProcessingFailure(DocumentScanViewPlugin.DocumentError documentError) {
                // this is called on any error while processing the document image
                // Note: this is called every time an error occurs in a run, so that might be quite often
                // An error message should only be presented to the user after some time

                //showErrorMessageFor(documentError);
            }

            @Override
            public void onPictureProcessingFailure(DocumentScanViewPlugin.DocumentError documentError) {

//                showErrorMessageFor(documentError, true);
//                if (progressDialog != null && progressDialog.isShowing()) {
//                    progressDialog.dismiss();
//                }
//
//                // if there is a problem, here is how images could be saved in the error case
//                // this will be a full, not cropped, not transformed image
//                AnylineImage image = ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).getCurrentFullImage();
//
//                if (image != null) {
//                    File outDir = new File(getCacheDir(), "error");
//                    outDir.mkdir();
//                    File outFile = new File(outDir, "" + System.currentTimeMillis() + documentError.name() + ".jpg");
//                    try {
//                        image.save(outFile, 100);
//                        Log.d(TAG, "error image saved to " + outFile.getAbsolutePath());
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//                    image.release();
//                }
            }

            @Override
            public boolean onDocumentOutlineDetected(List rect, boolean documentShapeAndBrightnessValid) {
                // is called when the outline of the document is detected. return true if the outline is consumed by
                // the implementation here, false if the outline should be drawn by the DocumentScanView
                lastOutline = rect; // saving the outline for the animations
                return false;
            }

            @Override
            public void onTakePictureSuccess() {
                // this is called after the image has been captured from the camera and is about to be processed
//                progressDialog = ProgressDialog.show(Document4Activity.this, getString(
//                        getResources().getIdentifier("document_processing_picture_header", "string", getPackageName())),
//                                                     getString(
//                                                             getResources().getIdentifier("document_processing_picture", "string", getPackageName())),
//                                                     true);

                if (errorMessageAnimator != null && errorMessageAnimator.isRunning()) {

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            errorMessageAnimator.cancel();
                            errorMessageLayout.setVisibility(View.GONE);
                        }
                    });

                }
            }

            @Override
            public void onTakePictureError(Throwable throwable) {
                // This is called if the image could not be captured from the camera (most probably because of an
                // OutOfMemoryError)
                Log.e(TAG, "onTakePictureError: This is called if the image could not be captured from the camera (most probably because of an OutOfMemoryError");
                throw new RuntimeException(throwable);
            }

            @Override
            public void onPictureCornersDetected(AnylineImage anylineImage, List list) {
                // this is called after manual corner detection was requested

                // save fullFrame
                //JSONObject
                jsonResult = new JSONObject();

                Log.d(TAG, "onPictureCornersDetected: This is called after manual corner detection was requested");
                try {
                    Log.d(TAG, "onPictureCornersDetected: TempFileUtil.createTempFileCheckCache");
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this, UUID.randomUUID().toString(), ".jpg");
                    Log.d(TAG, "onPictureCornersDetected: anylineImage.save");
                    anylineImage.save(imageFile, quality);
                    //manualResult.put("fullImagePath", imageFile.getAbsolutePath());
                    Log.d(TAG, "onPictureCornersDetected: imageFile.getAbsolutePath()");
                    jsonResult.put("fullImagePath", imageFile.getAbsolutePath());
                    jsonResult.put("outline", jsonForOutline(list));
                }  catch (Exception e) {
                    e.printStackTrace();
                }

                Log.d(TAG, "onPictureCornersDetected: documentScanView.getScanViewPlugin()");
                ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).transformPicture(anylineImage, list);
                Log.d(TAG, "onPictureCornersDetected: anylineImage.release");
                anylineImage.release();
            }


            @Override
            public void onPictureTransformed(AnylineImage anylineImage) {
                // this is called after a full frame image and 4 corners were passed to the SDK for
                // transformation (e.g. when a user manually selected the corners in an image)

                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // save fullFrame
                JSONObject jsonResult = new JSONObject();
                String result = new String();
                try {

                    // convert the transformed image into a gray scaled image internally
                    // transformedImage.getGrayCvMat(false);
                    // get the transformed image as bitmap
                    // Bitmap bmp = transformedImage.getBitmap();
                    // save the image with quality 100 (only used for jpeg, ignored for png)
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this,
                            UUID.randomUUID().toString(), ".jpg");
                    anylineImage.save(imageFile, quality);
                    //showToast(getString(getResources().getIdentifier("document_image_saved_to", "string", getPackageName())) + " " + imageFile.getAbsolutePath());

                    //showToast("Saved image");
                    jsonResult.put("imagePath", imageFile.getAbsolutePath());

                    FileInputStream fis = new FileInputStream(imageFile);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    Base64OutputStream base64out = new Base64OutputStream(baos, Base64.NO_WRAP);
                    byte[]buffer = new byte[1024];
                    int len = 0;
                    while ((len = fis.read(buffer)) >= 0) {
                        base64out.write(buffer, 0, len);
                    }
                    base64out.flush();
                    base64out.close();
                    /*
                     * Why should we close Base64OutputStream before processing the data:
                     * http://stackoverflow.com/questions/24798745/androidfiletobase64usingstreamingsometimesmissed2bytes
                     */

                    byte[]data = baos.toByteArray();

                    // Apply contrast
                    byte contrast = (byte)10;
                    int factor = (259 * (contrast + 255)) / (255 * (259 - contrast));
                    byte byteFactor = (byte)factor;
                    int inbetween;
                    int dataCounter = 0;
                    int dataLength = data.length;
                    while (dataCounter < dataLength) {
                        inbetween = factor * (data[dataCounter] - 128) + 128;
                        data[dataCounter] = (byte)inbetween;
                        inbetween = factor * (data[dataCounter + 1] - 128) + 128;
                        data[dataCounter + 1] = (byte)inbetween;
                        inbetween = factor * (data[dataCounter + 2] - 128) + 128;
                        data[dataCounter + 2] = (byte)inbetween;
                        dataCounter += 4;
                    }

                    result = new String(data, "UTF-8");

                    baos.close();
                    fis.close();
                    /**
                     * IMPORTANT: cache provided frames here, and release them at the end of this onResult. Because
                     * keeping them in memory (e.g. setting the full frame to an ImageView)
                     * will result in a OutOfMemoryError soon. This error is reported in {@link #onTakePictureError
                     * (Throwable)}
                     *
                     * Use a DiskCache http://developer.android.com/training/displayingbitmaps/cachebitmap.html#diskcache
                     * for example
                     *
                     */

                    // Put outline and conficence to result
                    jsonResult.put("imageData", result);
                    jsonResult.put("takenManual", "true");
                } 	catch(Exception e) {
                    String exceptionMessage = e.getMessage();
                    showToast(exceptionMessage);
                    try {
                        jsonResult.put("imageData", "Error: "+exceptionMessage);

                    } catch (Exception je) {
                        Log.e(TAG, "Error while putting image data to json.", je);
                    }
                }

                // release the images
                anylineImage.release();

                ResultReporter.onResult(jsonResult, true);
                setResult(AnylinePlugin.RESULT_OK);
                finish();
            }

            @Override
            public void onPictureTransformError(DocumentScanViewPlugin.DocumentError documentError) {
                // this is called on any error while transforming the document image from the 4 corners
                // Note: not implemented in this example
                Log.d(TAG, "Error with transforming.");
            }

        });

        // optionally stop the scan once a valid result was returned
        // documentScanView.setCancelOnResult(cancelOnResult);

    }


    private void showErrorMessageFor(DocumentScanViewPlugin.DocumentError documentError) {
        showErrorMessageFor(documentError, false);
    }

    private void showErrorMessageFor(DocumentScanViewPlugin.DocumentError documentError, boolean highlight) {
        String text = getString(getResources().getIdentifier("document_picture_error", "string", getPackageName()));
        switch (documentError) {
            case DOCUMENT_NOT_SHARP:
                text += getString(getResources().getIdentifier("document_error_not_sharp", "string", getPackageName()));
                break;
            case DOCUMENT_SKEW_TOO_HIGH:
                text += getString(getResources().getIdentifier("document_error_skew_too_high", "string", getPackageName()));
                break;
            case DOCUMENT_OUTLINE_NOT_FOUND:
                //text += getString(R.string.document_error_outline_not_found);
                return; // exit and show no error message for now!
            case IMAGE_TOO_DARK:
                text += getString(getResources().getIdentifier("document_error_too_dark", "string", getPackageName()));
                break;
            case SHAKE_DETECTED:
                text += getString(getResources().getIdentifier("document_error_shake", "string", getPackageName()));
                break;
            case DOCUMENT_BOUNDS_OUTSIDE_OF_TOLERANCE:
                text += getString(getResources().getIdentifier("document_error_closer", "string", getPackageName()));
                break;
            case DOCUMENT_RATIO_OUTSIDE_OF_TOLERANCE:
                text += getString(getResources().getIdentifier("document_error_format", "string", getPackageName()));
                break;
            case UNKNOWN:
                break;
            default:
                text += getString(getResources().getIdentifier("document_error_unknown", "string", getPackageName()));
                return; // exit and show no error message for now!
        }

        if (highlight) {
            showHighlightErrorMessageUiAnimated(text);
        } else {
            showErrorMessageUiAnimated(text);
        }
    }

    private void showErrorMessageUiAnimated(String message) {
        Log.d(TAG, "showErrorMessageUiAnimated start");
        if (lastErrorRecieved == 0) {
            // the cleanup takes care of removing the message after some time if the error did not show up again
            handler.post(errorMessageCleanup);
        }
        lastErrorRecieved = System.currentTimeMillis();
        if (errorMessageAnimator != null && (errorMessageAnimator.isRunning() || errorMessage.getText().equals
                (message))) {
            return;
        }

        errorMessageLayout.setVisibility(View.VISIBLE);
        errorMessage.setBackgroundColor(ContextCompat.getColor(this, getResources().getIdentifier("anyline_blue_darker", "color", getPackageName())));
        errorMessage.setAlpha(0f);
        errorMessage.setText(message);
        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", 0f, 1f);
        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
        errorMessageAnimator.setInterpolator(new DecelerateInterpolator());
        errorMessageAnimator.start();
        Log.d(TAG, "showErrorMessageUiAnimated end");
    }

    private void showHighlightErrorMessageUiAnimated(String message) {
        Log.d(TAG, "showHighlightErrorMessageUiAnimated start");
        lastErrorRecieved = System.currentTimeMillis();
        errorMessageLayout.setVisibility(View.VISIBLE);
        errorMessage.setBackgroundColor(ContextCompat.getColor(this, getResources().getIdentifier("anyline_red", "color", getPackageName())));
        errorMessage.setAlpha(0f);
        errorMessage.setText(message);

        if (errorMessageAnimator != null && errorMessageAnimator.isRunning()) {
            errorMessageAnimator.cancel();
        }

        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", 0f, 1f);
        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
        errorMessageAnimator.setInterpolator(new DecelerateInterpolator());
        errorMessageAnimator.setRepeatMode(ValueAnimator.REVERSE);
        errorMessageAnimator.setRepeatCount(1);
        errorMessageAnimator.start();
        Log.d(TAG, "showHighlightErrorMessageUiAnimated end");
    }

    private void showToast(String text) {
        try {
            notificationToast.setText(text);
        } catch (Exception e) {
            notificationToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        }
        notificationToast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //start the actual scanning
        documentScanView.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //stop the scanning
        documentScanView.stop();
        //release the camera (must be called in onPause, because there are situations where
        // it cannot be auto-detected that the camera should be released)
        documentScanView.releaseCameraInBackground();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onCameraOpened(CameraController cameraController, int width, int height) {
        //the camera is opened async and this is called when the opening is finished
        Log.d(TAG, "Camera opened successfully. Frame resolution " + width + " x " + height);
    }

    @Override
    public void onCameraError(Exception e) {
        //This is called if the camera could not be opened.
        // (e.g. If there is no camera or the permission is denied)
        // This is useful to present an alternative way to enter the required data if no camera exists.
        Log.e(TAG, "Error opening camera.", e);
        throw new RuntimeException(e);
    }

    @Override
    public void licenseKeyCheck(LicenseException licenseCheck) {
        finishWithError(licenseCheck.getLocalizedMessage());
    }


}
