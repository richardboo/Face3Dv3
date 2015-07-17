package com.example.jrme.face3dv3;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.jrme.face3dv3.fitting.CostFunction;
import com.example.jrme.face3dv3.util.IOHelper;
import com.example.jrme.face3dv3.util.MyProcrustes;
import com.example.jrme.face3dv3.util.Pixel;
import com.example.jrme.face3dv3.util.TextResourceReader;
import com.facepp.error.FaceppParseException;
import com.facepp.http.HttpRequests;
import com.facepp.http.PostParameters;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static com.example.jrme.face3dv3.Constants.BYTES_PER_FLOAT;
import static com.example.jrme.face3dv3.util.IOHelper.fileSize;
import static com.example.jrme.face3dv3.util.MatrixHelper.centroid;
import static com.example.jrme.face3dv3.util.MatrixHelper.translate;

import static com.example.jrme.face3dv3.util.IOHelper.readBinModel83Pt2DFloat;
import static com.example.jrme.face3dv3.util.IOHelper.readBinFloat;

/**
 * Get a picture form your phone<br />
 * Use the facepp api to detect face<br />
 * Find face on the picture, and mark its feature points.
 * Apply the Procruste Analysis method to match input model to the face
 * Extract the texture of the face
 * Show the face !
 * @author Jerome & Xiaoping
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    final private int PICTURE_CHOOSE = 1;
    private static final int REQUEST_IMAGE_CAPTURE = 2;

    private static final int DETECT_OK = 11;
    private static final int EXTRACT_OK = 12;
    private static final int SAVE_OK = 13;
    private static final int ERROR = 14;
    private ProgressDialog mProgressDialog;
    private String mCurrentPhotoPath;
    private static final String PICTURE_PATH = "picture path";

    private ImageView imageView = null;
    private Bitmap img = null;
    private Bitmap imgBeforeDetect = null;
    private Button buttonDetect = null;
    private Button buttonExtract = null;
    private Button buttonSave = null;
    private ImageButton buttonFace = null;
    private float imageWidth;
    private float imageHeight;

    RealMatrix xVicMatrix = new Array2DRowRealMatrix(83, 2);
    RealMatrix xBedMatrix = new Array2DRowRealMatrix(83, 2);
    RealMatrix xResult = new Array2DRowRealMatrix(83, 2);
    private float[][] initialPoints = new float[83][2];
    float rollAngle = 0.0f;

    List<Pixel> facePixels, modelPixels;

    private static final String CONFIG_DIRECTORY = "3DFace/DMM/config";
    private static final String MODEL_2D_83PT_FILE = "ModelPoints2D.dat";


    private static final String TEXTURE_DIRECTORY ="3DFace/DMM/Texture";
    private static final String TEXTURE_FILE = "averageTextureVector.dat";
    private static final int NUM_CASES = fileSize(TEXTURE_DIRECTORY, TEXTURE_FILE)/BYTES_PER_FLOAT;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        //check heap size
        ActivityManager am = ((ActivityManager)getSystemService(Activity.ACTIVITY_SERVICE));
        int largeMemory = am.getLargeMemoryClass();
        Log.d(TAG,"heap size = "+largeMemory);

        //get model points
        initialPoints = readBinModel83Pt2DFloat(CONFIG_DIRECTORY,MODEL_2D_83PT_FILE);

        buttonDetect = (Button) this.findViewById(R.id.button);
        buttonDetect.setEnabled(false);
        buttonDetect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                // show the dialog
                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Processing of Face Points Detection ...");
                processDectect();
            }
        });


        buttonExtract = (Button) this.findViewById(R.id.button2);
        buttonExtract.setEnabled(false);
        buttonExtract.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Processing of Face Extraction ...");
                processExtract();
            }
        });

        buttonSave = (Button) this.findViewById(R.id.button3);
        buttonSave.setEnabled(false);
        buttonSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {

                mProgressDialog = ProgressDialog.show(MainActivity.this, "Please wait",
                        "Saving Texture ...");
                processSave();
            }
        });

        buttonFace = (ImageButton) this.findViewById(R.id.imageButton);
        buttonFace.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                //start openGl activity
                final Intent intent = new Intent(MainActivity.this, OpenGLActivity.class);
                startActivity(intent);
            }
        });

        imageView = (ImageView) this.findViewById(R.id.imageView);
        imageView.setImageBitmap(img);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.camera:
                buttonDetect.setEnabled(false);
                buttonExtract.setEnabled(false);
                buttonSave.setEnabled(false);
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if(takePictureIntent.resolveActivity(getPackageManager()) != null){
                    // Create the File where the photo should go
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                        mCurrentPhotoPath = photoFile.getAbsolutePath();
                        Log.d(TAG,"image "+mCurrentPhotoPath);
                    } catch (IOException ex) {
                        // Error occurred while creating the File
                        ex.printStackTrace();
                        photoFile = null;
                        mCurrentPhotoPath = null;
                    }
                    // Continue only if the File was successfully created
                    if (photoFile != null) {
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                                Uri.fromFile(photoFile));
                        Log.d(TAG, "uri " + Uri.fromFile(photoFile));
                        MainActivity.this.startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
                        return true;
                    } else return false;
                }else return false;
            case R.id.gallery:
                buttonDetect.setEnabled(false);
                buttonExtract.setEnabled(false);
                buttonSave.setEnabled(false);
                //get a picture form your phone
                Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
                photoPickerIntent.setType("image/*");
                startActivityForResult(photoPickerIntent, PICTURE_CHOOSE);
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        //the image picker callback
        if (requestCode == PICTURE_CHOOSE) {
            if (intent != null) {
                //The Android api ~~~
                //Log.d(TAG, "idButSelPic Photopicker: " + intent.getDataString());
                Cursor cursor = getContentResolver().query(intent.getData(), null, null, null, null);
                cursor.moveToFirst();
                int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                String fileSrc = cursor.getString(idx);
                Log.d(TAG, "Picture : " + fileSrc);

                //just read size
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                img = BitmapFactory.decodeFile(fileSrc, options);
                //scale size to read
                options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max((double) options.outWidth /
                        1024f, (double) options.outHeight / 1024f)));
                options.inJustDecodeBounds = false;
                img = BitmapFactory.decodeFile(fileSrc, options);
                // detect the correct rotation
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(fileSrc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                String orientString = exif.getAttribute(ExifInterface.TAG_ORIENTATION);
                int orientation = orientString != null ? Integer.parseInt(orientString) :  ExifInterface.ORIENTATION_NORMAL;

                int rotationAngle = 0;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;
                // rotate image
                Matrix matrix = new Matrix();
                matrix.setRotate(rotationAngle, (float) img.getWidth() / 2, (float) img.getHeight() / 2);
                img = Bitmap.createBitmap(img, 0, 0, options.outWidth, options.outHeight, matrix, true);

                Log.d(TAG, "onActivityResult, img = " + img);
                imageView.setImageBitmap(img);
                buttonDetect.setEnabled(true);
                imgBeforeDetect = img;
                //build the victim matrix when image change
                for (int i = 0; i < 83; ++i) {
                    for (int j = 0; j < 2; ++j) {
                        xVicMatrix.setEntry(i, j, initialPoints[i][j]);
                    }
                }
                Log.d(TAG, "get Victim");
                Log.d(TAG, "image width = " + img.getWidth());
                Log.d(TAG, "image height = " + img.getHeight());
            } else {
                Log.d(TAG, "idButSelPic Photopicker canceled");
            }
        }
        // if results comes from the camera
        else if (requestCode == REQUEST_IMAGE_CAPTURE) {

            // if a picture was taken
            if (resultCode == Activity.RESULT_OK) {

                // Free the data of the last picture
                if(img != null || imgBeforeDetect != null){
                    img.recycle();
                    imgBeforeDetect.recycle();
                }

                String fileSrc = mCurrentPhotoPath;
                Log.d(TAG, "Picture:" + fileSrc);

                //just read size
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                img = BitmapFactory.decodeFile(fileSrc, options);
                //scale size to read
                options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max((double) options.outWidth /
                        1024f, (double) options.outHeight / 1024f)));
                options.inJustDecodeBounds = false;
                img = BitmapFactory.decodeFile(fileSrc, options);
                // detect the correct rotation
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(fileSrc);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                String orientString = exif.getAttribute(ExifInterface.TAG_ORIENTATION);
                int orientation = orientString != null ? Integer.parseInt(orientString) :  ExifInterface.ORIENTATION_NORMAL;

                int rotationAngle = 0;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotationAngle = 90;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotationAngle = 180;
                if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotationAngle = 270;
                // rotate image
                Matrix matrix = new Matrix();
                matrix.setRotate(rotationAngle, (float) img.getWidth() / 2, (float) img.getHeight() / 2);
                img = Bitmap.createBitmap(img, 0, 0, options.outWidth, options.outHeight, matrix, true);

                Log.d(TAG, "onActivityResult, img = " + img);
                imageView.setImageBitmap(img);

                galleryAddPic();
                mCurrentPhotoPath = null;
                buttonDetect.setEnabled(true);
                imgBeforeDetect = img;
                //build the victim matrix when image change
                for (int i = 0; i < 83; ++i) {
                    for (int j = 0; j < 2; ++j) {
                        xVicMatrix.setEntry(i, j, initialPoints[i][j]);
                    }
                }
                Log.d(TAG, "get Victim");
                Log.d(TAG, "image width = " + img.getWidth());
                Log.d(TAG, "image height = " + img.getHeight());

                // if user canceled from the camera activity
            } else if (resultCode == Activity.RESULT_CANCELED) {
                if(mCurrentPhotoPath != null){
                    File imageFile = new File(mCurrentPhotoPath);
                    imageFile.delete();
                }
            }
        }
    }

    private class FaceppDetect {
        DetectCallback callback = null;

        public void setDetectCallback(DetectCallback detectCallback) {
            callback = detectCallback;
        }

        public void detect(final Bitmap image) {

            new Thread(new Runnable() {

                public void run() {
                    Message msg = null;

                    HttpRequests httpRequests = new HttpRequests("16fcca64b37202c094f53672dc688e38", "ssIfcRfiMqU7jRuw1Yrw3VIlaXMclrj8", true, false);

                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    float scale = Math.min(1, Math.min(600f / img.getWidth(), 600f / img.getHeight()));
                    Matrix matrix = new Matrix();
                    matrix.postScale(scale, scale);

                    Bitmap imgSmall = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, false);
                    //Log.v(TAG, "imgSmall size : " + imgSmall.getWidth() + " " + imgSmall.getHeight());

                    imgSmall.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                    byte[] array = stream.toByteArray();
                    String faceId = null;

                    try {
                        //detect face
                        JSONObject result = httpRequests.detectionDetect(new PostParameters().setImg(array).setAttribute("glass,pose"));
                        System.out.println(result);
                        faceId = result.getJSONArray("face").getJSONObject(0).getString("face_id");
                        rollAngle = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("attribute")
                                .getJSONObject("pose").getJSONObject("roll_angle").getDouble("value");
                        imageWidth = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("position").getDouble("width");
                        imageHeight = (float) result.getJSONArray("face").getJSONObject(0).getJSONObject("position").getDouble("height");
                        Log.d("faceId", faceId);
                        Log.d("roll_angle", "" + rollAngle);
                        JSONObject result2 = httpRequests.detectionLandmark(new PostParameters().setFaceId(faceId));
                        Log.d(TAG, result2.toString());

                        //finished , then call the callback function
                        if (callback != null) {
                            msg = mHandler.obtainMessage(DETECT_OK);
                            callback.detectResult(result2); //landmark as the result (1 face only)
                        }
                    } catch (FaceppParseException e) {
                        e.printStackTrace();
                        msg = mHandler.obtainMessage(ERROR);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        msg = mHandler.obtainMessage(ERROR);
                    } finally{
                        // send the message
                        mHandler.sendMessage(msg);
                    }
                }
            }).start();
        }
    }

    interface DetectCallback {
        void detectResult(JSONObject rst);
    }

    public void showToast(final String toast) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void processDectect() {
        FaceppDetect faceppDetect = new FaceppDetect();
        faceppDetect.setDetectCallback(new DetectCallback() {

            public void detectResult(JSONObject rst) {

                Message msg = null;

                //use the red paint
                Paint paint = new Paint();
                paint.setColor(Color.RED);
                paint.setStrokeWidth(Math.max(img.getWidth(), img.getHeight()) / 100f);

                //create a new canvas
                Bitmap bitmap = Bitmap.createBitmap(img.getWidth(), img.getHeight(), img.getConfig());
                Canvas canvas = new Canvas(bitmap);
                canvas.drawBitmap(img, new Matrix(), null);

                // find landmark on 1 face only
                Iterator iterator;
                try {
                    int i = 0;
                    iterator = rst.getJSONArray("result").getJSONObject(0).getJSONObject("landmark").keys();

                    while (iterator.hasNext()) {
                        String key = iterator.next().toString();
                        JSONObject ori = rst.getJSONArray("result").getJSONObject(0).getJSONObject("landmark").getJSONObject(key);
                        float x = (float) ori.getDouble("x");
                        float y = (float) ori.getDouble("y");
                        x = x / 100 * img.getWidth();
                        y = y / 100 * img.getHeight();
                        canvas.drawPoint(x, y, paint);
                        xBedMatrix.setEntry(i, 0, x);
                        xBedMatrix.setEntry(i, 1, y);
                        i++;
                    }

                    //save new image
                    img = bitmap;
                    MainActivity.this.runOnUiThread(new Runnable() {

                        public void run() {
                            //show the image
                            imageView.setImageBitmap(img);
                            buttonExtract.setEnabled(true);
                        }
                    });

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
        faceppDetect.detect(img);
    }

    private void processExtract() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                Message msg = null;

                try {
                    //use the green paint
                    Paint paint = new Paint();
                    paint.setColor(Color.GREEN);
                    paint.setStrokeWidth(Math.max(img.getWidth(), img.getHeight()) / 100f);

                    //create a new canvas
                    Bitmap bitmap = Bitmap.createBitmap(img.getWidth(), img.getHeight(), img.getConfig());
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawBitmap(img, new Matrix(), null);

                    //draw initial model points
                    for (int i = 0; i < 83; i++) {
                        canvas.drawPoint((float) xVicMatrix.getEntry(i, 0), (float) xVicMatrix.getEntry(i, 1), paint);
                    }

                    MyProcrustes mProc = new MyProcrustes(xBedMatrix, xVicMatrix, rollAngle);
                    xResult = mProc.getProcrustes(); // 83 points result of the model
                    double distance = mProc.getProcrustesDistance();
                    Log.d(TAG, "procrustes distance = " + distance);
                    RealMatrix R = mProc.getR();
                    Log.d(TAG, "R = " + R);
                    double S = mProc.getS();
                    Log.d(TAG, "S = " + S);
                    RealMatrix T = mProc.getT();
                    Log.d(TAG, "T = " + T);

                    // load the 2D average shape
                    int cases = IOHelper.fileSize("3DFace/AverageFaceData/BinFiles", "averageFace2DNotScaled.dat") / BYTES_PER_FLOAT;
                    int k = cases / 2; // Number of Lines == Row Dimension
                    RealMatrix averageShape2D =
                            IOHelper.readBin2DShapetoMatrix("3DFace/AverageFaceData/BinFiles", "averageFace2DNotScaled.dat", k);

                    // adapt the translation matrix to the new dimension (64.140 rows)
                    double tx = T.getEntry(0, 0), ty = T.getEntry(0, 1);// +18;
                    RealMatrix tt = new Array2DRowRealMatrix(k, 2);
                    for (int i = 0; i < k; i++) {
                        tt.setEntry(i, 0, tx);
                        tt.setEntry(i, 1, ty);
                    }

                    // transform it
                    PointF c = centroid(averageShape2D), origin = new PointF(0, 0); // Recenter the points based on the mean
                    RealMatrix X0 = translate(averageShape2D, c, origin);
                    RealMatrix res = X0.multiply(R).scalarMultiply(S).add(tt);

                    // cover in white the face
                    paint.setColor(Color.WHITE);
                    for (int a = 0; a < res.getRowDimension(); a++) {
                        canvas.drawPoint((float) res.getEntry(a, 0), (float) res.getEntry(a, 1), paint);
                    }

                    //save new image
                    img = bitmap;
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() {
                            imageView.setImageBitmap(img);
                        }
                    });

                    // get the pixels of face from the previous image
                    facePixels = new ArrayList<>();
                    for (int i = 0; i < k; i++) {
                        try {
                            int x = (int) Math.ceil(res.getEntry(i, 0));
                            int y = (int) Math.ceil(res.getEntry(i, 1));
                            Pixel p = new Pixel(x, y, imgBeforeDetect.getPixel(x, y));
                            facePixels.add(i, p);
                        } catch (Exception e) {
                            Log.d(TAG, "Outside of the image");
                        }
                    }

                    Log.d(TAG, "pixels list size = " + facePixels.size());
                    //List<Pixel> diff = facePixels;
                    //        new ArrayList<>(new LinkedHashSet<>(pixels)); //delete pixels occurence

                    //draw on white face
                    for (Pixel p : facePixels) {
                        bitmap.setPixel(p.x,p.y,p.rgb);
                    }

                    //save new image
                    img = bitmap;
                    MainActivity.this.runOnUiThread(new Runnable() {
                        public void run() {
                            imageView.setImageBitmap(img);
                            buttonSave.setEnabled(true);
                        }
                    });

                    //modelPixels
                    float[] modelPixelsA = readBinFloat(TEXTURE_DIRECTORY, TEXTURE_FILE, NUM_CASES);
                    for(int i=0; i<NUM_CASES+1;i++){
                        //get r g b float

                        //build the modelPixels
                    }

                    //calculate Ei and Ef from Cost Function
                    CostFunction costFunc =  new CostFunction(facePixels,facePixels,xBedMatrix,xResult);
                    int Ei= costFunc.getEi();
                    Log.d(TAG,"Ei = "+Ei);
                    int Ef = costFunc.getEf();
                    Log.d(TAG,"Ef = "+Ef);

                    msg = mHandler.obtainMessage(EXTRACT_OK);
                } catch (Exception e) {
                    e.printStackTrace();
                    msg = mHandler.obtainMessage(ERROR);
                } finally {
                    // send the message
                    mHandler.sendMessage(msg);
                }
            }

        }).start();
    }

    private void processSave() {

        new Thread(new Runnable() {

            @Override
            public void run() {
                Message msg = null;

                try {
                    IOHelper.convertPixelsToBin(facePixels,"3DFace/AverageFaceData/BinFiles/faceTexture.dat");
                    msg = mHandler.obtainMessage(SAVE_OK);
                } catch (Exception e) {
                    e.printStackTrace();
                    msg = mHandler.obtainMessage(ERROR);
                } finally {
                    // send the message
                    mHandler.sendMessage(msg);
                }
            }

        }).start();
    }

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            AlertDialog.Builder builder;
            LayoutInflater inflater;
            View layout;
            TextView text;
            Toast toast;
            int menuBarId = MainActivity.this.getResources()
                    .getIdentifier("action_bar_default_height","dimen","android");
            int y = getResources().getDimensionPixelSize(menuBarId);

            switch (msg.what) {
                case DETECT_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Detected");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case EXTRACT_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Extracted");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case SAVE_OK:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_success,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Face Texture Saved");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
                case ERROR:
                    // Inflate the Layout
                    inflater = getLayoutInflater();
                    layout = inflater.inflate(R.layout.toast_error,
                            (ViewGroup) findViewById(R.id.custom_toast_id));
                    text = (TextView) layout.findViewById(R.id.text);
                    text.setText("Error during the process :\n -> Network error.\n -> No face. \n -> File reading error.");
                    // Create Custom Toast
                    toast = new Toast(MainActivity.this);
                    toast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, y);
                    toast.setDuration(Toast.LENGTH_SHORT);
                    toast.setView(layout);
                    toast.show();
                    break;
            }
            mProgressDialog.dismiss();
        }
    };

    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
        return image;
    }

    // add the taken image in the gallery application
    private void galleryAddPic() {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Log.d(TAG,"image path"+mCurrentPhotoPath);
        File f = new File(mCurrentPhotoPath);
        Uri contentUri = Uri.fromFile(f);
        mediaScanIntent.setData(contentUri);
        this.sendBroadcast(mediaScanIntent);
    }

    /* Some lifecycle callbacks so that the image location path can survive orientation change */
    @Override
    protected void onSaveInstanceState(Bundle outState){
        outState.putString(PICTURE_PATH, mCurrentPhotoPath);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState){
        super.onRestoreInstanceState(savedInstanceState);
        mCurrentPhotoPath = savedInstanceState.getString(PICTURE_PATH);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "onRestart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (img != null || imgBeforeDetect != null) {
            img.recycle();
            imgBeforeDetect.recycle();
        }
    }
}