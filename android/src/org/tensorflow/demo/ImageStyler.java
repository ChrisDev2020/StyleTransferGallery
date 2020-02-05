package org.tensorflow.demo;


import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.pm.PackageManager;
import android.media.Image.Plane;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;
import java.nio.ByteBuffer;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.R;


import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Vector;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;
import org.tensorflow.demo.OverlayView.DrawCallback;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;

import java.io.FileDescriptor;
import java.io.IOException;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;



public class ImageStyler extends Activity {




    // Tensorflow stuff

    private static final Logger LOGGER = new Logger();

    private TensorFlowInferenceInterface inferenceInterface;
    private static final String MODEL_FILE = "file:///android_asset/stylize_quantized.pb";
    private static final String INPUT_NODE = "input";
    private static final String STYLE_NODE = "style_num";
    private static final String OUTPUT_NODE = "transformer/expand/conv3/conv/Sigmoid";
    private static final int NUM_STYLES = 26;

    private static final boolean SAVE_PREVIEW_BITMAP = false;

    // Whether to actively manipulate non-selected sliders so that sum of activations always appears
    // to be 1.0. The actual style input tensor will be normalized to sum to 1.0 regardless.
    private static final boolean NORMALIZE_SLIDERS = true;

    private static final float TEXT_SIZE_DIP = 12;

    private static final boolean DEBUG_MODEL = false;

    private static final int[] SIZES = {128, 192, 256, 384, 512, 720};

    private static final Size DESIRED_PREVIEW_SIZE = new Size(1280, 720);


    private int desiredSizeIndex = -1;
    private int desiredSize = 256;
    private int initializedSize = 0;

    private Integer sensorOrientation;

    private int previewWidth = 0;
    private int previewHeight = 0;
    private byte[][] yuvBytes;
    private int[] rgbBytes = null;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap;



    private final float[] styleVals = new float[26];
    private int[] intValues;
    private float[] floatValues;
    private int frameNum = 0;


    private Bitmap textureCopyBitmap;

    private boolean computing = false;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;
    private Handler handler;
    private HandlerThread handlerThread;


    //Image Loader stuff
    private static int RESULT_LOAD_IMAGE = 1;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(null);
        LOGGER.d("onCreate " + this);

        System.out.print("Programm started");



        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.imagestyler);

        inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILE);



        for(int i = 0 ; i< 26; i++){

            float result = 0.0f;
            if(i == 5){
                result = 1.0f;
            }else{
                result = 0.0f;
            }

            styleVals[i]= result;
            System.out.println("styleVals[i]:" + i + " " + styleVals[i]);
        }

        System.out.println("styleVals[i]:" + " " + styleVals[5]);
        InitGUI();
    }


     void InitGUI() {


        Button buttonLoadImage = (Button) findViewById(R.id.buttonLoad);
        buttonLoadImage.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View arg0) {

                Intent i = new Intent(
                        Intent.ACTION_PICK,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

                startActivityForResult(i, RESULT_LOAD_IMAGE);
            }
        });
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }



    public void requestRender() {
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }
    }

   //@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data ) {
        //super.onActivityResult(requestCode, resultCode, data);

        System.out.println("resultCode: " + resultCode);
        if(resultCode != -1){
            //System.out.println("not an image load call");
        //return;
        }

        System.out.print("onActivityResult called");
        if (requestCode == RESULT_LOAD_IMAGE && resultCode == RESULT_OK && null != data) {
            Uri selectedImage = data.getData();
            String[] filePathColumn = { MediaStore.Images.Media.DATA };

            Cursor cursor = getContentResolver().query(selectedImage,
                    filePathColumn, null, null, null);
            cursor.moveToFirst();

            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);
            cursor.close();

            ImageView imageView = (ImageView) findViewById(R.id.previewImage);

            Bitmap bmp = null;
            try {
                bmp = getBitmapFromUri(selectedImage);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            imageView.setImageBitmap(bmp);

            croppedBitmap = Bitmap.createBitmap(256, 256, Config.ARGB_8888);
            cropCopyBitmap = Bitmap.createBitmap(bmp);
            stylizeImage(bmp);

             /*  runInBackground(
                 new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("inside runner");
                            stylizeImage(cropCopyBitmap);
                            handler.postDelayed(this, 1000);
                            requestRender();
                            computing = false;
                        }
                    });*/
        }
    }



    public void recreateImage(final ImageReader reader) {


            if (desiredSize != initializedSize)
            {

                previewWidth = 256;
                previewHeight = previewWidth;

                desiredSize = 256;


                rgbBytes = new int[previewWidth * previewHeight];

                rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
                croppedBitmap = Bitmap.createBitmap(desiredSize, desiredSize, Config.ARGB_8888);

                frameToCropTransform =
                        ImageUtils.getTransformationMatrix(
                                previewWidth, previewHeight,
                                desiredSize, desiredSize,
                                sensorOrientation, true);

                cropToFrameTransform = new Matrix();
                frameToCropTransform.invert(cropToFrameTransform);

                yuvBytes = new byte[3][];

                intValues = new int[desiredSize * desiredSize];
                floatValues = new float[desiredSize * desiredSize * 3];
                initializedSize = desiredSize;
            }
        }

    private Bitmap getBitmapFromUri(Uri uri) throws IOException {
        ParcelFileDescriptor parcelFileDescriptor =
                getContentResolver().openFileDescriptor(uri, "r");
        FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
        Bitmap image = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        parcelFileDescriptor.close();
        return image;
    }

    private void stylizeImage( Bitmap bitmap) {

        System.out.println("stylizeImage - called");
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        //intValues = new int[w * h];
        floatValues = new float[w * h * 3];
        initializedSize = w;

        intValues = new int[w*h]; // oder 4???

        bitmap.getPixels(intValues, 0, w, 0, 0, w, h);
        System.out.println("bitmap.getWidth(): " + bitmap.getWidth());
        //bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());


       // floatValues = new float [intValues.length*4];


        System.out.println("bitmap get pixels success");

            for (int i = 0; i < intValues.length; ++i) {

                if(i >  intValues.length-100){
                    System.out.println("current index "+ intValues.length + ": " + i);
                }

                final int val = intValues[i];
                floatValues[i * 3] = ((val >> 16) & 0xFF) / 255.0f;
                floatValues[i * 3 + 1] = ((val >> 8) & 0xFF) / 255.0f;
                floatValues[i * 3 + 2] = (val & 0xFF) / 255.0f;
            }

        System.out.println("floatValues length: "+ floatValues.length );


        System.out.println("float conversion success");

        for(int i = 0 ; i< NUM_STYLES; i++){


            float result = 0.0f;
            if(i == 5){
                result = 1.0f;
            }else{
                result = 0.0f;
            }

            styleVals[i]= result;
        }

        for(int i = 0 ; i< styleVals.length; i++) {

            System.out.println("styleVals[i]: "+"i: " + i + " " + styleVals[i]);
        }


        // Copy the input data into TensorFlow.

        System.out.println("Setting tensorflow INPUT_NODE");
        inferenceInterface.feed(INPUT_NODE, floatValues, 1, bitmap.getWidth(), bitmap.getHeight(), 3);



        System.out.println("Setting tensorflow STYLE_NODE");
        inferenceInterface.feed(STYLE_NODE, styleVals, NUM_STYLES);



        System.out.println("Execute the output node's dependency sub-graph");
        // Execute the output node's dependency sub-graph.
        inferenceInterface.run(new String[] {OUTPUT_NODE}, false);

        System.out.println("Generate Tensorflow image");
        // Copy the data from TensorFlow back into our array.



        inferenceInterface.fetch(OUTPUT_NODE, floatValues);
        System.out.println("Generate Tensorflow image _ done");

        for (int i = 0; i < intValues.length; ++i) {
            intValues[i] =
                    0xFF000000
                            | (((int) (floatValues[i * 3] * 255)) << 16)
                            | (((int) (floatValues[i * 3 + 1] * 255)) << 8)
                            | ((int) (floatValues[i * 3 + 2] * 255));
        }

        //bitmap.setPixels(intValues, 0, w, 0, 0, w, h);

        Bitmap outpoutBitmap = Bitmap.createBitmap(bitmap);



        outpoutBitmap.copyPixelsFromBuffer(IntBuffer.wrap(intValues));


        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;


        bitmap = bitmap.copy( Bitmap.Config.ARGB_8888 , true);

        bitmap.setPixels(intValues, 0, w, 0, 0, w, h);
        ImageView imageView = (ImageView) findViewById(R.id.previewImage);
        imageView.setImageBitmap(outpoutBitmap);
        final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
        if (overlay != null) {
            overlay.postInvalidate();
        }

    }

    public boolean isDebug() {
        return false;
    }

}
